#!/usr/bin/env python3
"""Проверка собранного APK: разрешения, нативный код, динамическая загрузка кода,
сетевые адреса, признаки майнинга/слежки. Сравнивает с ожиданиями из lock.json.
Код выхода 1 — найдено нарушение (сборка не попадёт в релиз)."""
import json, os, re, subprocess, sys, zipfile

apk, lid = sys.argv[1], sys.argv[2]
lock = {l["id"]: l for l in json.load(open("lock.json"))["launchers"]}[lid]
bt = sys.argv[3]  # путь к build-tools
problems, notes = [], []

# 1. Разрешения
out = subprocess.run([os.path.join(bt, "aapt2"), "dump", "permissions", apk], capture_output=True, text=True).stdout
perms = sorted(set(re.findall(r"uses-permission: name='([^']+)'", out)))
dyn_ok = lambda p: p == lock["pkg"] + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
unexpected = [p for p in perms if p not in lock["perms"] and not dyn_ok(p)]
if unexpected:
    problems.append("Неожиданные разрешения: " + ", ".join(unexpected))
badging = subprocess.run([os.path.join(bt, "aapt2"), "dump", "badging", apk], capture_output=True, text=True).stdout
m = re.search(r"package: name='([^']+)'", badging)
if not m or m.group(1) != lock["pkg"]:
    problems.append("Имя пакета не совпадает: %s" % (m.group(1) if m else "?"))

# 2. Содержимое архива
z = zipfile.ZipFile(apk)
names = z.namelist()
native = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
if native:
    problems.append("Нативные библиотеки (.so) в APK: " + ", ".join(native[:10]))
nested = [n for n in names if re.search(r"\.(apk|jar|dex|zip)$", n) and not re.match(r"classes\d*\.dex$", n)]
if nested:
    problems.append("Вложенные исполняемые архивы: " + ", ".join(nested[:10]))

# 3. Строки из dex
strings = set()
for n in names:
    if re.match(r"classes\d*\.dex$", n):
        data = z.read(n)
        for s in re.findall(rb"[\x20-\x7e]{6,}", data):
            strings.add(s.decode("ascii", "ignore"))
blob = "\n".join(strings)
SAFE_HOSTS = {"schemas.android.com", "www.w3.org", "developer.android.com", "ns.adobe.com", "xmlpull.org",
              "issuetracker.google.com", "goo.gle", "d.android.com", "www.apache.org", "github.com", "en.wikipedia.org"}
hosts = sorted(set(re.findall(r"https?://([A-Za-z0-9.-]+)", blob)))
extra_hosts = [h for h in hosts if h not in SAFE_HOSTS and h not in lock["hosts"]]
if extra_hosts:
    problems.append("Неожиданные сетевые адреса: " + ", ".join(extra_hosts))
ips = sorted(set(re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b", blob)) - {"0.0.0.0", "127.0.0.1", "255.255.255.255"})
ips = [i for i in ips if not re.match(r"^(\d+\.\d+\.\d+\.\d+)$", i) or not all(int(x) < 10 for x in i.split("."))]
if ips:
    notes.append("IP-подобные строки (проверить вручную): " + ", ".join(ips[:10]))
DANGER = {
    "динамическая загрузка кода": r"Ldalvik/system/(DexClassLoader|InMemoryDexClassLoader|PathClassLoader);",
    "запуск команд оболочки": r"Ljava/lang/Runtime;.*exec|Ljava/lang/ProcessBuilder;",
    "майнинг": r"(?i)stratum\+tcp|xmrig|cryptonight|coinhive|monero|nicehash|minerd",
    "SMS/звонки/контакты": r"Landroid/telephony/SmsManager;|Landroid/provider/(ContactsContract|CallLog)",
    "запись звука/экрана/камера": r"Landroid/media/(AudioRecord|MediaRecorder);|Landroid/media/projection/|Landroid/hardware/camera2?/",
    # getDeviceId сам по себе есть и у KeyEvent/InputDevice (пульт), поэтому — только вместе с TelephonyManager
    "идентификаторы устройства": r"Landroid/telephony/TelephonyManager;|getSubscriberId|getImei|getMacAddress|getSerial$",
    "учётные записи": r"Landroid/accounts/AccountManager;",
}
found = {k: bool(re.search(v, blob, re.M)) for k, v in DANGER.items()}
for k, v in found.items():
    if v:
        hits = sorted(x for x in strings if re.search(DANGER[k], x))[:3]
        problems.append("Обнаружено: %s (%s)" % (k, "; ".join(h[:80] for h in hits)))
if "getDeviceId" in strings and not found["идентификаторы устройства"]:
    notes.append("getDeviceId без TelephonyManager — это номер устройства ввода (пульт), не идентификатор ТВ")

report = {"id": lid, "name": lock["name"], "pkg": lock["pkg"], "commit": lock["commit"], "repo": lock["repo"],
          "permissions": perms, "hosts": [h for h in hosts if h not in SAFE_HOSTS], "native_libs": native,
          "checks": {k: ("найдено" if v else "нет") for k, v in found.items()},
          "problems": problems, "notes": notes, "manual_audit": lock["audit"],
          "ok": not problems}
os.makedirs("out", exist_ok=True)
json.dump(report, open("out/%s.report.json" % lid, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(report, ensure_ascii=False, indent=1))
sys.exit(1 if problems else 0)
