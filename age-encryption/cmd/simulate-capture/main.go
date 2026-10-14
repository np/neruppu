// simulate-capture exercises the exact code path the Android app will use,
// from a desktop, so the prototype can be verified without a device.
//
//	simulate-capture -r age1... -in capture.jpg -out capture.jpg.age
//
// It calls EncryptBytes, which is what MediaStorageRepositoryImpl.saveImage
// calls through gomobile.
package main

import (
	"flag"
	"fmt"
	"os"

	neruppuage "org.havenapp.neruppu/age"
)

func main() {
	recipient := flag.String("r", "", "age X25519 recipient (age1...)")
	in := flag.String("in", "", "plaintext capture")
	out := flag.String("out", "", "encrypted output")
	flag.Parse()

	if *recipient == "" || *in == "" || *out == "" {
		flag.Usage()
		os.Exit(2)
	}

	if !neruppuage.IsValidRecipient(*recipient) {
		fmt.Fprintln(os.Stderr, "invalid recipient")
		os.Exit(1)
	}

	plaintext, err := os.ReadFile(*in)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	ciphertext, err := neruppuage.EncryptBytes(plaintext, *recipient)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	if err := os.WriteFile(*out, ciphertext, 0o600); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	fmt.Printf("%d B plaintext -> %d B ciphertext (+%d B, %.2f%%)\n",
		len(plaintext), len(ciphertext),
		len(ciphertext)-len(plaintext),
		100*float64(len(ciphertext)-len(plaintext))/float64(len(plaintext)))
}
