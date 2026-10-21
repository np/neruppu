package neruppuage

import (
	"bytes"
	"io"
	"strings"

	"filippo.io/age"
)

// Remote-control support. These functions are for the REMOTE (controller)
// phone only: it owns the age identity and shows the guarded phone's photos.
// The guarded phone never generates or stores an identity, so on that device
// nothing can be decrypted, as before.

// GenerateIdentity returns a new age X25519 identity ("AGE-SECRET-KEY-1...").
func GenerateIdentity() (string, error) {
	id, err := age.GenerateX25519Identity()
	if err != nil {
		return "", err
	}
	return id.String(), nil
}

// RecipientOf returns the public key ("age1...") of an identity.
func RecipientOf(identity string) (string, error) {
	id, err := age.ParseX25519Identity(strings.TrimSpace(identity))
	if err != nil {
		return "", err
	}
	return id.Recipient().String(), nil
}

// DecryptBytes decrypts a complete age file with the given identity.
func DecryptBytes(ciphertext []byte, identity string) ([]byte, error) {
	id, err := age.ParseX25519Identity(strings.TrimSpace(identity))
	if err != nil {
		return nil, err
	}
	r, err := age.Decrypt(bytes.NewReader(ciphertext), id)
	if err != nil {
		return nil, err
	}
	return io.ReadAll(r)
}
