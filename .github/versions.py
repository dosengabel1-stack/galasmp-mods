# Holt die passenden Versionen fuer Minecraft 1.21.11 und schreibt gradle.properties.
import json, re, sys, urllib.request
MC = "1.21.11"

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (galasmp-mods build)"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode()
    except Exception as e:
        print(f"::error::Abruf fehlgeschlagen: {url} -> {e}")
        sys.exit(1)

def versions(url):
    return re.findall(r"<version>([^<]+)</version>", get(url))

def numeric(vs):
    vs = [v for v in vs if re.fullmatch(r"\d+(\.\d+)+", v)]
    return sorted(vs, key=lambda v: [int(x) for x in v.split(".")])

loader = next(v["version"] for v in json.loads(get("https://meta.fabricmc.net/v2/versions/loader")) if v.get("stable"))
api = [v for v in versions("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml") if v.endswith("+" + MC)]
if not api:
    print(f"::error::Keine Fabric-API-Version fuer {MC} gefunden")
    sys.exit(1)
loom = numeric(versions("https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml"))
mm = numeric(versions("https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml"))
props = f"""org.gradle.jvmargs=-Xmx2G
minecraft_version={MC}
loader_version={loader}
fabric_version={api[-1]}
loom_version={loom[-1]}
modmenu_version={mm[-1]}
"""
print(f"::notice::Versionen: loader={loader} api={api[-1]} loom={loom[-1]} modmenu={mm[-1]}")
for m in ("oreglow", "orderalert"):
    open(f"{m}/gradle.properties", "w").write(props)
