"""Run Light Chat checks on a fresh emulator installation using the debug demo."""
from pathlib import Path
import base64
import json
import re
import sys
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", "emulator-5554"]
APP = "sg.lightchat"
ACTIVITY = f"{APP}/com.thelightphone.sdk.LightActivity"
DUMP = "/sdcard/light-chat-test.xml"


def adb(*args, data=None):
    return subprocess.run(ADB + list(args), input=data, capture_output=True, check=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", DUMP)
    return list(ET.fromstring(adb("shell", "cat", DUMP)).iter("node"))


def find(label, items):
    searched = label.lower()
    exact = next((n for n in items if any(searched == n.get(a, "").lower() for a in ("text", "content-desc"))), None)
    if exact is not None:
        return exact
    return next((n for n in items if any(searched in n.get(a, "").lower() for a in ("text", "content-desc"))), None)


def show():
    return " | ".join(n.get("text", "") for n in nodes() if n.get("text"))


def verify(label):
    items = nodes()
    if find(label, items) is None:
        raise AssertionError(f"Missing {label!r}: " + " | ".join(n.get("text", "") for n in items))
    print(f"PASS {label}", flush=True)


def tap_node(node):
    x, y, x2, y2 = tuple(int(i) for i in re.findall(r"[0-9]+", node.get("bounds", "")))
    if x2 <= x or y2 <= y:
        return False
    adb("shell", "input", "tap", str((x + x2) // 2), str((y + y2) // 2))
    time.sleep(0.2)
    return True


def tap_last(label):
    for _ in range(4):
        items = nodes()
        matches = [node for node in items if node.get("text") == label]
        if matches and tap_node(matches[-1]):
            return
        scroll = next((node for node in items if node.get("scrollable") == "true"), None)
        if scroll is not None:
            x1, y1, x2, y2 = tuple(int(i) for i in re.findall(r"[0-9]+", scroll.get("bounds", "")))
            x = str((x1 + x2) // 2)
            adb("shell", "input", "swipe", x, str(y2 - 25), x, str(y1 + 25), "300")
    raise AssertionError(f"Could not tap the last {label!r}: {show()}")


def tap(label, scroll=False):
    for _ in range(8 if scroll else 2):
        items = nodes()
        node = find(label, items)
        if node is not None and tap_node(node):
            return
        if scroll:
            adb("shell", "input", "swipe", "550", "1000", "550", "330", "320")
        time.sleep(0.2)
    raise AssertionError(f"Could not tap {label!r}: {show()}")


def back():
    tap("back")


def type_text(value):
    adb("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(0.2)


def enter(label, value):
    tap(label)
    type_text(value)
    if b"mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.3)


def start():
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(0.8)


def debug_file(filename, content):
    adb("shell", "run-as", APP, "mkdir", "-p", "files/shared/imports")
    adb("shell", "run-as", APP, "sh", "-c", f"'cat > files/shared/imports/{filename}'", data=content)


def open_demo():
    adb("shell", "am", "force-stop", APP)
    start()
    verify("New chat")
    tap("Settings")
    tap("Turn on emulator demo", scroll=True)
    verify("Emulator demo")
    back()


def basic():
    open_demo()
    tap("New chat")
    enter("Message", "Emulator first question")
    tap("Send")
    verify("Demo reply")
    verify("example/demo:free")
    tap("Answer options")
    verify("19 input")
    tap("Retry in a new branch")
    verify("Branch of")
    verify("Emulator first question")
    back()
    verify("Emulator first question")
    verify("Branch of")

    tap("One-off chat")
    enter("Message", "Private fixture")
    tap("Send")
    verify("Demo reply")
    back()
    if "Private fixture" in show():
        raise AssertionError("One-off chat was saved")
    print("PASS one-off chat stayed out of history", flush=True)

    tap("New chat")
    enter("Message", "simulate failure")
    tap("Send")
    verify("Simulated network error")
    verify("Choose another free model")
    verify("simulate failure")
    back()
    verify("simulate failure")

def advanced():
    open_demo()
    debug_file("fixture.txt", b"Public test fixture only.")
    tap("New chat")
    tap("Attach")
    tap("fixture.txt")
    verify("fixture.txt")
    tap("Send")
    verify("Send to OpenRouter")
    tap("Send")
    verify("Attached fixture.txt")

    tap("Auto Free")
    tap("Gemini")
    tap("Gemini 3.8 Flash", scroll=True)
    verify("Full chat")
    tap("Confirm switch")
    tap("Options")
    tap("High", scroll=True)
    tap("Read URL", scroll=True)
    enter("Message", "Read https://example.com")
    tap("Send")
    verify("project’s billing and quota")
    tap("Send")
    research_and_live()


def research_and_live():
    tap("Sources (1)")
    verify("Example source")
    back()
    tap("Voice")
    tap("Start live voice")
    tap("Start")
    verify("Demo live session")
    tap("Mute reply")
    verify("Speak to interrupt")
    tap("Stop")
    verify("Start live voice")
    back()
    back()


def media():
    open_demo()
    tap("New chat")
    debug_file("photo.png", base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jUioAAAAASUVORK5CYII="))
    tap("Attach")
    tap("photo.png")
    tap("Send")
    tap("Send")
    verify("Attached photo.png")
    tap("Answer options")
    tap("Read aloud")
    tap("Read with OpenRouter Free")
    tap("Read")
    tap("Pause")
    verify("Paused")
    tap("Stop")
    verify("Read with OpenRouter Free")
    back()
    record()


def record():
    tap("Dictate")
    verify("Listening")
    tap("Finish dictation")
    verify("Simulated dictation from the emulator")


def microphone_retry():
    open_demo()
    tap("One-off chat")
    button = find("Dictate", nodes())
    assert button is not None
    x1, y1, x2, y2 = tuple(int(i) for i in re.findall(r"[0-9]+", button.get("bounds", "")))
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    adb("shell", "input", "swipe", x, y, x, y, "1600")
    verify("Simulated dictation from the emulator")


def ux():
    open_demo()
    tap("One-off chat")
    tap("Auto Free")
    tap("Gemini")
    tap("All models")
    tap("Free-tier models")
    enter("Search models", "Flash Lite")
    verify("Gemini 3.1 Flash-Lite")
    verify("Free tier available")
    tap("Gemini 3.1 Flash-Lite")
    verify("Full chat")
    verify("Question only")
    tap("Confirm switch")

    debug_file("lc-ux-guide.txt", b"Public test attachment.")
    try:
        tap("Attach")
        tap("lc-ux-guide.txt")
        verify("Text · Send once")
        tap("Gemini · Question only")
        verify("What gets sent")
        verify("lc-ux-guide.txt (Text)")
        tap("Done")
        enter("Message", "Format demo answer")
        tap("Send")
        tap("Send")
        verify("Example heading")
        if find("Text · Send once", nodes()) is not None:
            raise AssertionError("One-time attachment remained in the composer after send.")
        print("PASS two-provider model selection, sharing preview and send-once attachment", flush=True)

        tap("Attach")
        tap("lc-ux-guide.txt")
        tap("Text · Send once")
        tap("Keep for follow-ups")
        verify("Text · Keep for follow-ups")
        enter("Message", "Follow up on the test file")
        tap("Send")
        tap("Send")
        verify("Text · Keep for follow-ups")
        tap("Remove")
        print("PASS follow-up attachment stayed until removed", flush=True)

        tap("Voice")
        tap("Start live voice")
        tap("Start")
        verify("Demo live session")
        tap("Stop")
        verify("Start live voice")
        back()
        back()

        tap("New chat")
        enter("Message", "A unique UI test draft")
        back()
        verify("A unique UI test draft")
        tap("A unique UI test draft")
        tap("Options")
        tap("Delete")
        tap("Delete")
    finally:
        adb("shell", "run-as", APP, "rm", "-f", "files/shared/imports/lc-ux-guide.txt")


def persistence():
    adb("shell", "am", "force-stop", APP)
    start()
    verify("Emulator first question")
    verify("simulate failure")
    print("PASS saved messages and failed draft survived restart", flush=True)
    tap("Settings")
    tap("Keep until I delete", scroll=True)
    verify("Change history retention")
    tap("Apply")
    verify("Keep until I delete")
    tap("Days (1–3650)", scroll=True)
    type_text("30")
    if b"mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "4")
    tap("Set days", scroll=True)
    tap("Apply")
    saved = json.loads(adb("exec-out", "run-as", APP, "cat", "files/chats.json"))
    assert saved.get("preferences", {}).get("retentionDays", 30) == 30
    print("PASS reset history retention to 30 days", flush=True)


def network():
    adb("shell", "am", "force-stop", APP)
    start()
    tap("New chat")
    tap("Auto Free")
    verified = False
    for _ in range(4):
        visible = [node.get("text", "") for node in nodes()]
        if any("Favorite" == label for label in visible) and visible.count("Favorite") > 1:
            verified = True
            break
    if not verified:
        raise AssertionError("The public OpenRouter catalog did not show manual free models: " + show())
    print("PASS public OpenRouter catalog from the emulator", flush=True)
    back()
    back()
    tap("Settings")
    tap("OpenRouter")
    invalid_key()


def invalid_key():
    field = next(node for node in nodes() if node.get("class") == "android.widget.EditText")
    tap_node(field)
    type_text("fake-emulator-key")
    if b"mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "4")
    tap("Check and save")
    verify("OpenRouter rejected this key")
    back()
    verify("Add API key")


if __name__ == "__main__":
    suites = {"ux": ux, "basic": basic, "advanced": advanced, "persistence": persistence, "media": media, "research_and_live": research_and_live, "network": network, "record": record, "microphone": microphone_retry, "invalid_key": invalid_key}
    for suite in sys.argv[1:] or ("basic", "advanced", "persistence", "media"):
        suites[suite]()
