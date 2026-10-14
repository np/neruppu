package neruppuage

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"filippo.io/age"
)

// newKeypair returns (publicKey, privateKeyFileContents).
func newKeypair(t *testing.T) (string, string) {
	t.Helper()
	id, err := age.GenerateX25519Identity()
	if err != nil {
		t.Fatalf("GenerateX25519Identity: %v", err)
	}
	return id.Recipient().String(), id.String()
}

// decryptWithCLI shells out to the real `age` binary, which is the point:
// we verify interoperability with the tool the user will actually run,
// not with our own library calls.
func decryptWithCLI(t *testing.T, ciphertext []byte, identity string) []byte {
	t.Helper()
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "key.txt")
	if err := os.WriteFile(keyPath, []byte(identity+"\n"), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	ctPath := filepath.Join(dir, "capture.age")
	if err := os.WriteFile(ctPath, ciphertext, 0o600); err != nil {
		t.Fatalf("write ciphertext: %v", err)
	}
	cmd := exec.Command("age", "-d", "-i", keyPath, ctPath)
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		t.Fatalf("age -d failed: %v (stderr: %s)", err, errb.String())
	}
	return out.Bytes()
}

func TestEncryptBytesRoundTripViaCLI(t *testing.T) {
	pub, priv := newKeypair(t)

	// A plausible capture: a JPEG SOI/EOI envelope around some payload.
	plaintext := append([]byte{0xFF, 0xD8, 0xFF, 0xE0}, bytes.Repeat([]byte("neruppu"), 5000)...)
	plaintext = append(plaintext, 0xFF, 0xD9)

	ct, err := EncryptBytes(plaintext, pub)
	if err != nil {
		t.Fatalf("EncryptBytes: %v", err)
	}
	if bytes.Contains(ct, []byte("neruppu")) {
		t.Fatal("plaintext leaked into ciphertext")
	}
	if !bytes.HasPrefix(ct, []byte("age-encryption.org/v1\n")) {
		t.Fatalf("not a plain age file, got prefix %q", ct[:min(40, len(ct))])
	}

	got := decryptWithCLI(t, ct, priv)
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("round trip mismatch: %d bytes in, %d bytes out", len(plaintext), len(got))
	}
}

func TestWriterToFdRoundTripViaCLI(t *testing.T) {
	pub, priv := newKeypair(t)

	dir := t.TempDir()
	outPath := filepath.Join(dir, "out.age")
	f, err := os.Create(outPath)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	// Mimic ParcelFileDescriptor.detachFd(): hand the raw fd over and stop
	// touching the *os.File ourselves.
	fd, err := dupFd(f)
	if err != nil {
		t.Fatalf("dup: %v", err)
	}
	f.Close()

	w, err := NewWriterToFd(fd, pub)
	if err != nil {
		t.Fatalf("NewWriterToFd: %v", err)
	}

	// Write in many small chunks, as CameraX does through an OutputStream.
	plaintext := bytes.Repeat([]byte("0123456789abcdef"), 4096) // 64 KiB, crosses an age STREAM chunk
	for off := 0; off < len(plaintext); {
		end := off + 997 // deliberately not a round number
		if end > len(plaintext) {
			end = len(plaintext)
		}
		n, err := w.Write(plaintext[off:end])
		if err != nil {
			t.Fatalf("write: %v", err)
		}
		off += n
	}
	if err := w.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	ct, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("read back: %v", err)
	}
	got := decryptWithCLI(t, ct, priv)
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("round trip mismatch: %d in, %d out", len(plaintext), len(got))
	}
}

// TestUnclosedWriterIsUndecryptable documents the failure mode that matters
// operationally: if the process dies mid-capture, the partial file is lost,
// not merely truncated. Worth surfacing in the UI.
func TestUnclosedWriterIsUndecryptable(t *testing.T) {
	pub, priv := newKeypair(t)
	dir := t.TempDir()
	outPath := filepath.Join(dir, "partial.age")
	f, err := os.Create(outPath)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	fd, err := dupFd(f)
	if err != nil {
		t.Fatalf("dup: %v", err)
	}
	f.Close()

	w, err := NewWriterToFd(fd, pub)
	if err != nil {
		t.Fatalf("NewWriterToFd: %v", err)
	}
	if _, err := w.Write(bytes.Repeat([]byte("x"), 200000)); err != nil {
		t.Fatalf("write: %v", err)
	}
	// Deliberately no Close().

	ct, _ := os.ReadFile(outPath)
	keyPath := filepath.Join(dir, "key.txt")
	os.WriteFile(keyPath, []byte(priv+"\n"), 0o600)
	ctPath := filepath.Join(dir, "p.age")
	os.WriteFile(ctPath, ct, 0o600)
	if err := exec.Command("age", "-d", "-i", keyPath, ctPath).Run(); err == nil {
		t.Fatal("expected an unfinalized age stream to fail decryption")
	}
}

func TestRecipientValidation(t *testing.T) {
	pub, priv := newKeypair(t)

	if !IsValidRecipient(pub) {
		t.Fatal("valid recipient rejected")
	}
	if !IsValidRecipient("  " + pub + "\n") {
		t.Fatal("whitespace should be tolerated")
	}
	for _, bad := range []string{"", "age1nonsense", "ssh-ed25519 AAAAC3Nz", priv} {
		if IsValidRecipient(bad) {
			t.Fatalf("accepted invalid recipient %q", bad)
		}
	}

	// A private key must never be usable as a recipient: that is how a secret
	// would end up on the device by accident.
	if _, err := EncryptBytes([]byte("x"), priv); err == nil {
		t.Fatal("private key accepted as recipient")
	}
	if _, err := EncryptBytes([]byte("x"), ""); err == nil {
		t.Fatal("empty recipient accepted")
	}
}

func TestMultipleRecipients(t *testing.T) {
	pub1, priv1 := newKeypair(t)
	pub2, priv2 := newKeypair(t)

	ct, err := EncryptBytes([]byte("hello"), strings.Join([]string{pub1, pub2}, "\n"))
	if err != nil {
		t.Fatalf("EncryptBytes: %v", err)
	}
	for _, priv := range []string{priv1, priv2} {
		if got := decryptWithCLI(t, ct, priv); string(got) != "hello" {
			t.Fatalf("got %q", got)
		}
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
