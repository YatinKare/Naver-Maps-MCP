package worker

import (
	"math"
	"strconv"
	"strings"
)

// Read only bounded stage labels and finite durations from the private worker.
// Never log arbitrary response headers or upstream content.
func captureStages(header string) map[string]float64 {
	if len(header) > 1024 {
		return nil
	}
	allowed := map[string]bool{
		"browser_startup": true, "page_navigation": true, "search_input": true,
		"response_capture": true, "browser_shutdown": true,
	}
	var stages map[string]float64
	for _, item := range strings.Split(header, ",") {
		name, duration, ok := strings.Cut(strings.TrimSpace(item), ";dur=")
		if !ok || !allowed[name] {
			continue
		}
		milliseconds, err := strconv.ParseFloat(duration, 64)
		if err != nil || math.IsNaN(milliseconds) || math.IsInf(milliseconds, 0) || milliseconds < 0 || milliseconds > 3600000 {
			continue
		}
		if stages == nil {
			stages = make(map[string]float64)
		}
		stages[name] = milliseconds
	}
	return stages
}
