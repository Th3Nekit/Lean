package libcore

import (
	"context"
	"sync"
	"testing"
	"time"
)

func TestExchangeContextSuccessCompletesLookupWait(t *testing.T) {
	done := make(chan struct{})
	exchange := &ExchangeContext{
		context: context.Background(),
		done: sync.OnceFunc(func() {
			close(done)
		}),
	}

	exchange.Success("1.1.1.1")

	select {
	case <-done:
	case <-time.After(250 * time.Millisecond):
		t.Fatal("successful lookup did not complete the Exchange wait")
	}

	if len(exchange.addresses) != 1 || exchange.addresses[0].String() != "1.1.1.1" {
		t.Fatalf("unexpected lookup result: %v", exchange.addresses)
	}
}
