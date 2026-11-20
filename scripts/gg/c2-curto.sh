#!/usr/bin/env bash
# C2 curto no lab GoldenGate: regime TAXA ev/s (padrao 1000) por DURACAO_SEG (padrao 300), Extract + Replicat ja em execucao.
# Mede latencia por evento (aplicado_em - origem_updated_at), tendencia do lag, taxa do gerador, relogios; ao fim drena, reconcilia
# (SQL e jar Java `reconciliacao` MODO=completo DRY_RUN=true) e confere que nenhum UUID pre-existente (snapshot de c1) mudou.
# Saida: resultados/gg/c2/<execucao>/ (meta.json pequeno; CSVs ignorados pelo git). Uso: scripts/gg/c2-curto.sh [rotulo]
# Variaveis: TAXA, DURACAO_SEG, THREADS, SEMENTE, PCT_INSERT, PCT_DELETE, HOT_PCT (padroes do C2), SNAPSHOT_BASE (padrao
# resultados/gg/c1/snapshots/apos-carga.csv). Saida 0 = todos os criterios aprovados; 1 = algum reprovado/invalido.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); cd "$ROOT"
set -a; source scripts/lab-gg.env; set +a
TAXA=${TAXA:-1000}; DUR=${DURACAO_SEG:-300}; ROT=${1:-regime}
EXE=$(date -u +%Y%m%dT%H%M%SZ)-$ROT; D=resultados/gg/c2/$EXE; mkdir -p "$D"
BASE=${SNAPSHOT_BASE:-resultados/gg/c1/snapshots/apos-carga.csv}
[[ -s $BASE ]] || { echo "snapshot base ausente: $BASE" >&2; exit 2; }
source gg/lib.sh
for p in "extracts/EXTBDAD" "replicats/REPBDAD"; do [[ $(gg GET /$p/info/status | jq -r .response.status) == running ]] || { echo "$p nao esta running" >&2; exit 2; }; done
rel() { python3 scripts/medir-relogios.py | awk '{printf "{\"offset_ms\":%.3f,\"incerteza_ms\":%.3f}", $1/1e6, $2/1e6}'; }
scripts/gg/drenar.sh 900
printf "truncate table aplicacao_log;\nexit\n" | scripts/gg/q.sh novo >/dev/null
rel > "$D/relogio-inicio.json"
scripts/gg/gerar.sh "$TAXA" "$DUR" "$D" | tee "$D/gerar.txt"
rel > "$D/relogio-fim.json"
ini=$(sed -n 's/.*inicio_carga=//p' "$D/gerador.log"); fim=$(sed -n 's/.*fim_carga=//p' "$D/gerador.log")
scripts/gg/drenar.sh 900 | tee "$D/drenagem.txt"
scripts/gg/hb-estagios.sh $(( (DUR + 120) / 60 )) > "$D/heartbeat-estagios.txt"
OFF=$(jq -r .offset_ms "$D/relogio-inicio.json")   # offset desenv-server - infra-server; latencia = aplicado(infra) - updated_at(desenv)
scripts/gg/latencia-janela.sh "$ini" "$fim" "$D" "$(awk -v o="$OFF" 'BEGIN{print -o}')" >/dev/null
# reconciliacao (SQL) e Java; UUIDs
scripts/gg/reconciliar.sh > "$D/reconciliacao-sql.txt" 2>&1 && REC_SQL=0 || REC_SQL=$?
JAR=poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar
LEGADO_URL=jdbc:oracle:thin:@//192.168.0.5:1523/FREEPDB1 NOVO_URL=jdbc:oracle:thin:@//192.168.0.11:1524/FREEPDB1 MODO=completo DRY_RUN=true \
  java -Xmx512m -jar "$JAR" reconciliacao > "$D/reconciliacao-java.log" 2>&1 && REC_JAVA=0 || REC_JAVA=$?
scripts/gg/snapshot-ids.sh "c2/$EXE" apos-c2 > "$D/snapshot.txt"
awk -F, 'NR==FNR{b[$1","$2]=$3; next} ($1","$2) in b {pre++; if (b[$1","$2]!=$3) mud++} END{printf "{\"preexistentes_comparados\":%d,\"uuids_alterados\":%d}\n", pre, mud+0}' "$BASE" "$D/snapshots/apos-c2.csv" > "$D/uuids.json"
# meta.json
python3 -I - "$D" "$EXE" "$TAXA" "$DUR" "$REC_SQL" "$REC_JAVA" <<'PY'
import json, re, sys, hashlib, pathlib
d, exe, taxa, dur, rs, rj = sys.argv[1:7]; D = pathlib.Path(d)
log = (D / "gerador.log").read_text()
m = re.search(r"linhas=(\d+) transacoes=(\d+) taxa=([\d.]+) alvo=(\d+) transacao_media_ms=([\d.]+)", log)
lat = json.loads((D / "latencia.json").read_text()); uu = json.loads((D / "uuids.json").read_text())
r0 = json.loads((D / "relogio-inicio.json").read_text()); r1 = json.loads((D / "relogio-fim.json").read_text())
sat = {}
for f in D.glob("recursos-*.csv"):
    for line in f.read_text().splitlines():
        t = line.split(",")
        if len(t) >= 3:
            n = re.sub(r"gg-gerador-\d+", "gerador", t[1]); v = float(t[2].rstrip("%"))
            e = sat.setdefault(n, [0, 0.0, 0.0]); e[0] += 1; e[1] += v; e[2] = max(e[2], v)
cpu = {k: {"media_pct": round(v[1] / v[0], 1), "max_pct": v[2], "amostras": v[0]} for k, v in sat.items()}
tr = lat["tendencia_max_por_5s"]; tm = lat["tendencia_media_por_5s"]
off = max(abs(r0["offset_ms"]) + r0["incerteza_ms"], abs(r1["offset_ms"]) + r1["incerteza_ms"])
v = lambda ok: "APROVADO" if ok else "REPROVADO"
taxa_ef = float(m[3])
crit = {
 "p95_ate_5s": {"valor_s": lat["p95_s"], "alvo_s": 5, "veredito": v(lat["p95_s"] <= 5)},
 "lag_sem_tendencia_de_alta": {"inclinacao_max_ms_s": tr["inclinacao_ms_s"], "crescimento_sustentado_max": tr["crescimento_sustentado"],
   "inclinacao_media_ms_s": tm["inclinacao_ms_s"], "crescimento_sustentado_media": tm["crescimento_sustentado"], "janela_s": tr["janela_s"],
   "veredito": "INVALIDO" if tr["crescimento_sustentado"] is None else v(not tr["crescimento_sustentado"])},
 "taxa_ate_98pct": {"valor": taxa_ef, "alvo": int(taxa), "minimo": int(taxa) * .98, "veredito": v(taxa_ef >= int(taxa) * .98)},
 "relogios_ate_100ms": {"pior_offset_mais_incerteza_ms": round(off, 3), "veredito": v(off <= 100)},
 "integridade": {"reconciliacao_sql_divergencias": "0" if rs == "0" else "ver reconciliacao-sql.txt", "reconciliacao_java_exit": int(rj),
   "uuids": uu, "veredito": v(rs == "0" and rj == "0" and uu["uuids_alterados"] == 0 and uu["preexistentes_comparados"] > 0)}}
meta = {"execucao": exe, "alvo_ev_s": int(taxa), "duracao_s": int(dur), "replicat": "REPBDAD classico",
 "gerador": {"linhas": int(m[1]), "transacoes": int(m[2]), "taxa_efetiva": taxa_ef, "transacao_media_ms": float(m[5])},
 "latencia_s": {k: lat[k] for k in ("n", "min_s", "p50_s", "p95_s", "p99_s", "max_s", "media_s")} | {"metodo": lat["metodo"]},
 "relogios": {"inicio": r0, "fim": r1}, "cpu_docker_stats": cpu, "criterios": crit,
 "params_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()[:16] for p in pathlib.Path("gg/params").glob("*.prm")},
 "veredito": "APROVADO" if all(c["veredito"] == "APROVADO" for c in crit.values()) else "REPROVADO"}
(D / "meta.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
print(json.dumps({"veredito": meta["veredito"], "latencia_s": meta["latencia_s"], "criterios": {k: c["veredito"] for k, c in crit.items()}}, ensure_ascii=False))
sys.exit(0 if meta["veredito"] == "APROVADO" else 1)
PY
