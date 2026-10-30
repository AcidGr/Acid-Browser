#!/usr/bin/env python3
"""
Acid Browser MCP Stdio Bridge
Bridges standard input/output (stdio) MCP clients (e.g. Claude Desktop, Cursor)
to Acid Browser's native MCP Server running on Android (http://127.0.0.1:3090/mcp).
"""

import sys
import os
import json
import urllib.request

MCP_ENDPOINT = os.environ.get("ACID_MCP_ENDPOINT", "http://127.0.0.1:3090/mcp")
MCP_TOKEN = os.environ.get("ACID_MCP_TOKEN", "")

# Parse simple CLI args: --token <token> or --endpoint <url>
for i in range(1, len(sys.argv)):
    if sys.argv[i] == "--token" and i + 1 < len(sys.argv):
        MCP_TOKEN = sys.argv[i + 1]
    elif sys.argv[i] == "--endpoint" and i + 1 < len(sys.argv):
        MCP_ENDPOINT = sys.argv[i + 1]

def main():
    headers = {'Content-Type': 'application/json'}
    if MCP_TOKEN:
        headers['Authorization'] = f'Bearer {MCP_TOKEN}'

    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            req_data = json.loads(line)
            req = urllib.request.Request(
                MCP_ENDPOINT,
                data=json.dumps(req_data).encode('utf-8'),
                headers=headers
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp_text = resp.read().decode('utf-8')
                sys.stdout.write(resp_text + "\n")
                sys.stdout.flush()
        except Exception as e:
            err_resp = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32603, "message": str(e)}
            }
            sys.stdout.write(json.dumps(err_resp) + "\n")
            sys.stdout.flush()

if __name__ == "__main__":
    main()
