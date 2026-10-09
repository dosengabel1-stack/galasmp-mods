# Holt die passenden Versionen fuer Minecraft 1.21.11 und schreibt gradle.properties.
import json, re, urllib.request
MC = "1.21.11"
def get(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return r.read().decode()
loader = next(v["version"] for v in json.loads(get("https://meta.fabricmc.net/v2/versions/loader")) if v.get("stable"))
api = [v for v in re.findall(r"<version>([^<]+)</version>", get("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml")) if v.endswith("+" + MC)]
loom = [v for v in re.findall(r"<version>([^<]+)</version>", get("https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml")) if re.fullmatch(r"\d+\.\d+\.\d+", v)]
loom.sort(key=lambda v: [int(x) for x in v.split(".")])
mm = [v for v in re.findall(r"<version>([^<]+)</version>", get("https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml")) if re.fullmatch(r"\d+\.\d+\.\d+", v)]
mm.sort(key=lambda v: [int(x) for x in v.split(".")])
props = f"""org.gradle.jvmargs=-Xmx2G
minecraft_version={MC}
loader_version={loader}
fabric_version={api[-1]}
loom_version={loom[-1]}
modmenu_version={mm[-1]}
"""
print(props)
for m in ("oreglow", "orderalert"):
    open(f"{m}/gradle.properties", "w").write(props)
