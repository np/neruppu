package neruppuage

import (
	"os"
	"syscall"
)

// dupFd duplicates the file's descriptor so tests can hand a raw fd to
// NewWriterToFd the way ParcelFileDescriptor.detachFd() does on Android.
func dupFd(f *os.File) (int, error) {
	fd, err := syscall.Dup(int(f.Fd()))
	if err != nil {
		return -1, err
	}
	return fd, nil
}
