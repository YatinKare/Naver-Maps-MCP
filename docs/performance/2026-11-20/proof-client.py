#!/usr/bin/env python3
"""Manual live MCP client for the terminal recording; no mocked responses."""
import datetime
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[3] if 'docs/performance' in str(Path(__file__).resolve()) else Path('/home/yatink/projects/map-data-fetcher')
spec = importlib.util.spec_from_file_location('live_benchmark', repo / 'scripts/benchmark-mcp.py')
module = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(module)
client = module.MCP('http://127.0.0.1:13000/mcp', 120)
print('NAVER Maps | actual HTTP MCP tools/call | isolated integration build', flush=True)
print('UTC:', datetime.datetime.now(datetime.timezone.utc).isoformat(), flush=True)
print('Endpoint: http://127.0.0.1:13000/mcp', flush=True)
init, _ = client.call('initialize', {'protocolVersion': client.protocol, 'capabilities': {},
    'clientInfo': {'name': 'naver-live-proof-agent', 'version': '1.0'}})
client.protocol = init['result']['protocolVersion']
client.call('notifications/initialized', notification=True)
listing, _ = client.call('tools/list')
print('Discovered:', ', '.join(t['name'] for t in listing['result']['tools']), flush=True)
arguments = {'query': '카페', 'longitude': 126.9784, 'latitude': 37.5667, 'page': 2}
print('\n> tools/call naver_map_coordinate_search', flush=True)
print(json.dumps(arguments, ensure_ascii=False), flush=True)
response, timing = client.call('tools/call', {'name': 'naver_map_coordinate_search', 'arguments': arguments})
places = module.places_from(response)
if timing['http_status'] != 200 or not places:
    print('FAILED:', timing, flush=True)
    sys.exit(1)
print('\nHTTP 200 | success | returned', len(places), 'places', flush=True)
print('End-to-end response body received in %.3f seconds' % (timing['wall_ms'] / 1000), flush=True)
print('\nFirst 2 places (selected fields from the actual response):', flush=True)
for place in places[:2]:
    print(json.dumps({key: place[key] for key in ('id', 'name', 'road_address', 'coordinates', 'tel') if key in place},
        ensure_ascii=False, indent=2), flush=True)
print('All response IDs SHA256:', hashlib.sha256('\n'.join(sorted(str(p['id']) for p in places)).encode()).hexdigest(), flush=True)
output = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/tmp/naver-proof-response.json')
output.write_text(json.dumps({'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'arguments': arguments, 'timing': timing, 'response': response}, ensure_ascii=False, indent=2) + '\n')
print('\nRecorded response:', output.name, flush=True)
print('PRs #69 runner | #70 session | #71 capture/performance | no deployment or merge', flush=True)
