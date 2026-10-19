// Package watch evaluates --until conditions against stream events.
package watch

import (
	"encoding/json"

	"github.com/itchyny/gojq"
)

// Until is a compiled jq condition.
type Until struct{ code *gojq.Code }

func Compile(src string) (*Until, error) {
	q, err := gojq.Parse(src)
	if err != nil {
		return nil, err
	}
	code, err := gojq.Compile(q)
	if err != nil {
		return nil, err
	}
	return &Until{code: code}, nil
}

// Match reports whether the first output of the condition on data is truthy
// (anything but false and null). A runtime error counts as no match.
func (u *Until) Match(data json.RawMessage) (bool, error) {
	var v any
	if err := json.Unmarshal(data, &v); err != nil {
		return false, err
	}
	out, ok := u.code.Run(v).Next()
	if !ok {
		return false, nil
	}
	if err, isErr := out.(error); isErr {
		return false, err
	}
	return Truthy(out), nil
}

func Truthy(v any) bool {
	return v != nil && v != false
}
