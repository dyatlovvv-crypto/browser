#!/usr/bin/env python3
"""Click Google SERP «Режим ИИ» inside our WebView via DevTools (not Chrome app)."""
from __future__ import annotations

import asyncio
import json
import subprocess
import sys
import time
import urllib.request

APP = "ru.srr.safari"


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True).strip()


def pid() -> str:
    return adb("shell", "pidof", "-s", APP)


def forward(p: str) -> None:
    subprocess.call(["adb", "forward", "--remove", "tcp:9222"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    adb("forward", "tcp:9222", f"localabstract:webview_devtools_remote_{p}")


def pages() -> list:
    with urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5) as r:
        return json.load(r)


CLICK_JS = r"""
(() => {
  const a = [...document.querySelectorAll('a')].find(
    (e) => (e.innerText || '').trim() === 'Режим ИИ' && e.getBoundingClientRect().width > 2
  );
  if (!a || !a.href) return { ok: false, reason: 'no-anchor' };
  a.click();
  return { ok: true, href: a.href.slice(0, 160) };
})()
"""


async def click_ai(ws_url: str) -> dict:
    import websockets

    async with websockets.connect(ws_url, max_size=8_000_000) as ws:
        async def call(method, params=None, id_=1):
            msg = {"id": id_, "method": method}
            if params is not None:
                msg["params"] = params
            await ws.send(json.dumps(msg))
            while True:
                data = json.loads(await ws.recv())
                if data.get("id") == id_:
                    return data

        await call("Runtime.enable", id_=1)
        res = await call(
            "Runtime.evaluate",
            {"expression": CLICK_JS, "returnByValue": True},
            id_=2,
        )
        return (res.get("result") or {}).get("result") or {}


def main() -> int:
    p = pid()
    if not p:
        print("ERROR: app not running", file=sys.stderr)
        return 2
    forward(p)
    time.sleep(0.4)
    targets = [t for t in pages() if "google.com/search" in t.get("url", "")]
    if not targets:
        print("ERROR: no google search WebView target", file=sys.stderr)
        return 3
    page = targets[0]
    print("before:", page.get("url", "")[:120])
    try:
        import websockets  # noqa: F401
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "websockets", "-q"])
    value = asyncio.get_event_loop().run_until_complete(click_ai(page["webSocketDebuggerUrl"]))
    print("click:", value)
    if not (value.get("value") or {}).get("ok") and not value.get("ok"):
        # websockets result shape: {type, value: {ok:…}}
        inner = value.get("value") if isinstance(value.get("value"), dict) else value
        if not inner.get("ok"):
            print("ERROR: click failed", inner, file=sys.stderr)
            return 4
    # Wait until udm=50 (AI Mode) or timeout
    for i in range(20):
        time.sleep(0.5)
        try:
            cur = next(
                (t for t in pages() if "google.com/search" in t.get("url", "")),
                None,
            )
        except Exception:
            continue
        if cur and "udm=50" in cur.get("url", ""):
            print("after:", cur["url"][:120])
            print("AI_TAB_OK")
            return 0
    print("ERROR: AI Mode URL not reached", file=sys.stderr)
    return 5


if __name__ == "__main__":
    sys.exit(main())
