//go:build tools

// Keeps golang.org/x/mobile in go.mod: `gomobile bind` refuses to run
// unless the module requires it, and `go mod tidy` would otherwise drop it.
package neruppuage

import _ "golang.org/x/mobile/bind"
