#!/usr/bin/env bash
# Compara os eventos do CSV do gerador (instante,tabela,codigo) com o aplicacao_log do gg-novo por (tabela,codigo):
# contagens, chaves com diferenca e explicacao (mesmo codigo repetido na mesma transacao do gerador).
# Uso: scripts/gg/comparar-eventos.sh DIR   (DIR com gerador-linhas.csv; grava aplicacao-log.csv e comparacao-eventos.json)
# Requer que o aplicacao_log tenha sido truncado antes da carga e drenado depois. Imprime o JSON.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); D=${1:?DIR}
printf "set heading off feedback off pagesize 0 linesize 200 trimspool on\nselect tabela||','||codigo||','||op from aplicacao_log;\nexit\n" | "$ROOT/scripts/gg/q.sh" novo | tr -d '\r' | grep -E '^[A-Z_]+,[0-9]+,[IU]$' > "$D/aplicacao-log.csv"
python3 -I - "$D" <<'PY'
import collections, csv, json, sys, pathlib
D = pathlib.Path(sys.argv[1])
ger = collections.Counter(); tx = collections.defaultdict(list); op = collections.Counter()
rows = list(csv.reader((D / "gerador-linhas.csv").read_text().splitlines()))[1:]
for inst, t, c in rows:
    ger[(t, int(c))] += 1; tx[inst].append((t, int(c)))
log = collections.Counter()
for line in (D / "aplicacao-log.csv").read_text().splitlines():
    t, c, o = line.split(","); log[(t, int(c))] += 1; op[o] += 1
# transacao = linhas com o mesmo instante de registro (o gerador grava todas as linhas de uma tx com um unico instante)
dup_na_tx = sum(len(v) - len(set(v)) for v in tx.values())
chaves = set(ger) | set(log)
falt = {k: ger[k] - log[k] for k in chaves if ger[k] > log[k]}; exc = {k: log[k] - ger[k] for k in chaves if log[k] > ger[k]}
r = {"eventos_gerador": sum(ger.values()), "linhas_log": sum(log.values()), "transacoes": len(tx),
     "repeticoes_mesma_chave_na_mesma_transacao": dup_na_tx, "chaves_com_menos_no_log": len(falt), "eventos_faltantes_total": sum(falt.values()),
     "chaves_com_mais_no_log": len(exc), "eventos_a_mais_total": sum(exc.values()), "ops_log": dict(op)}
dif = sum(falt.values()) - sum(exc.values())
r["diferenca_liquida"] = dif
r["sem_perda"] = dif == 0 and not falt and not exc  # cada linha do gerador = uma linha do log, por (tabela,codigo)
(D / "comparacao-eventos.json").write_text(json.dumps(r, indent=1) + "\n"); print(json.dumps(r))
PY
