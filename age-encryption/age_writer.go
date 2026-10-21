// Package neruppuage is a thin gomobile binding around filippo.io/age.
// (Decryption exists only for the remote-control phone: see remote.go.)
//
// Design notes (differ deliberately from Cryptocam's shim):
//   - Output is a PLAIN age file: no magic number, no recipient fingerprints,
//     no custom container. `age -d -i key.txt capture.age > capture.jpg` must
//     yield a directly usable file, with any age implementation.
//   - Only the public key ever reaches the guarded device: a seized phone
//     cannot read what it produced. The decryption functions in remote.go are
//     for the remote-control phone, which holds the identity.
//
// Build the AAR with ./build-aar.sh (see BUILD.md).
package neruppuage

import (
	"bytes"
	"errors"
	"io"
	"os"
	"strings"

	"filippo.io/age"
)

// Writer wraps the age encrypting WriteCloser.
//
// gobind cannot export io.WriteCloser, so this struct is the bridge. Write
// returns the number of bytes consumed; callers must honour short writes
// (the Kotlin side does; see AgeOutputStream).
type Writer struct {
	w io.WriteCloser
	// f is kept so the underlying file is closed exactly once, after the age
	// stream is finalized. Nil for the in-memory path.
	f *os.File
}

func (w *Writer) Write(data []byte) (int, error) {
	return w.w.Write(data)
}

// Close finalizes the age stream (writing the last STREAM chunk and its tag)
// and then closes the underlying file. Failing to call this truncates the
// payload: age cannot decrypt an unfinalized stream.
func (w *Writer) Close() error {
	err := w.w.Close()
	if w.f != nil {
		if ferr := w.f.Close(); err == nil {
			err = ferr
		}
	}
	return err
}

// parseRecipients accepts one or more "age1..." X25519 public keys separated
// by newlines. gobind does not support slices of interfaces, hence the string.
func parseRecipients(recipients string) ([]age.Recipient, error) {
	trimmed := strings.TrimSpace(recipients)
	if trimmed == "" {
		return nil, errors.New("no recipient configured")
	}
	parsed, err := age.ParseRecipients(strings.NewReader(trimmed))
	if err != nil {
		return nil, err
	}
	if len(parsed) == 0 {
		return nil, errors.New("no recipient configured")
	}
	// Reject anything that is not X25519 (e.g. an ssh recipient pasted by
	// mistake): the threat model assumes nothing secret sits on the device,
	// and scrypt recipients would mean a passphrase on the phone.
	for _, r := range parsed {
		if _, ok := r.(*age.X25519Recipient); !ok {
			return nil, errors.New("only age X25519 recipients are supported")
		}
	}
	return parsed, nil
}

// NewWriterToFd takes ownership of fd and returns a Writer that encrypts
// everything written to it. The caller must call Close.
//
// fd must come from ParcelFileDescriptor.detachFd() on the Kotlin side, so
// that the Android runtime does not close it behind our back.
func NewWriterToFd(fd int, recipients string) (*Writer, error) {
	parsed, err := parseRecipients(recipients)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), "neruppu-capture")
	if f == nil {
		return nil, errors.New("invalid file descriptor")
	}
	enc, err := age.Encrypt(f, parsed...)
	if err != nil {
		f.Close()
		return nil, err
	}
	return &Writer{w: enc, f: f}, nil
}

// EncryptBytes is the one-shot path, for payloads already held in memory
// (a JPEG byte array, a short text alert). Returns a complete age file.
func EncryptBytes(plaintext []byte, recipients string) ([]byte, error) {
	parsed, err := parseRecipients(recipients)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	enc, err := age.Encrypt(&buf, parsed...)
	if err != nil {
		return nil, err
	}
	if _, err := enc.Write(plaintext); err != nil {
		return nil, err
	}
	if err := enc.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// IsValidRecipient reports whether key is a usable age X25519 public key.
// Used to validate what the user pastes or scans in settings.
func IsValidRecipient(key string) bool {
	_, err := age.ParseX25519Recipient(strings.TrimSpace(key))
	return err == nil
}
