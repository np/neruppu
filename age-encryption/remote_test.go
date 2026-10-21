package neruppuage

import (
	"bytes"
	"strings"
	"testing"
)

func TestRemoteIdentityRoundTrip(t *testing.T) {
	identity, err := GenerateIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(identity, "AGE-SECRET-KEY-1") {
		t.Fatalf("unexpected identity format: %.20s", identity)
	}
	recipient, err := RecipientOf(identity)
	if err != nil {
		t.Fatal(err)
	}
	if !IsValidRecipient(recipient) {
		t.Fatalf("recipient %q not accepted by IsValidRecipient", recipient)
	}

	plaintext := []byte("jpeg bytes \x00\xff")
	ciphertext, err := EncryptBytes(plaintext, recipient)
	if err != nil {
		t.Fatal(err)
	}
	// Ours and the real CLI agree.
	got, err := DecryptBytes(ciphertext, identity)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plaintext) {
		t.Fatal("DecryptBytes round trip mismatch")
	}
	if !bytes.Equal(decryptWithCLI(t, ciphertext, identity), plaintext) {
		t.Fatal("age CLI cannot decrypt for the generated identity")
	}
}

func TestDecryptWithWrongIdentityFails(t *testing.T) {
	recipient, _ := newKeypair(t)
	_, otherIdentity := newKeypair(t)
	ciphertext, err := EncryptBytes([]byte("x"), recipient)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := DecryptBytes(ciphertext, otherIdentity); err == nil {
		t.Fatal("expected failure with the wrong identity")
	}
	if _, err := RecipientOf("age1notanidentity"); err == nil {
		t.Fatal("a recipient must not parse as an identity")
	}
}
