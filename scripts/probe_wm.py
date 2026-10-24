import json, re, urllib.request, urllib.error, sys
UA = "VibeLauncher-TVDoctor/1.0 (https://github.com/Magomedali81/tv-launchers-build)"
def get(url, accept=None):
    h = {"User-Agent": UA}
    if accept: h["Accept"] = accept
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:300]
for d in sys.argv[1:]:
    for feed in ["https://ru.wikipedia.org/api/rest_v1/feed/featured/", "https://en.wikipedia.org/api/rest_v1/feed/featured/"]:
        st, body = get(feed + d, "application/json")
        print("::notice title=feed %s %s::%s %s" % (d, feed.split('/')[2], st, body[:200] if st != 200 else ""))
        if st != 200: continue
        im = json.loads(body).get("image") or {}
        o, t = im.get("image", {}), im.get("thumbnail", {})
        print("::notice title=img %s::orig %s %sx%s | thumb %s" % (d, o.get("source"), o.get("width"), o.get("height"), t.get("source")))
        tu = re.sub(r"[?#].*$", "", t.get("source", ""))
        for w in (1920, 1280, 960):
            u = re.sub(r"/(lossy-page1-|page1-)?\d+px-([^/]+)$", lambda m: "/" + (m.group(1) or "") + str(w) + "px-" + m.group(2), tu)
            st2, b2 = get(u)
            print("::notice title=thumb %s %d::%s %s %s" % (d, w, st2, u, b2[:150] if st2 != 200 else len(b2)))
        break
