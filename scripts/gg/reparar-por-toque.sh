#!/usr/bin/env bash
# Reparo por "toque": reaplica no legado (com Extract RUNNING e supplemental logging ativo) um UPDATE sem mudanca de negocio (excluido = excluido; o trigger
# *_updated renova updated_at) nas chaves distintas de um CSV do gerador (instante,tabela,codigo). O Extract captura a linha INTEIRA (ALLCOLS) e o Replicat a aplica
# (CDR USEMAX: updated_at novo vence), convergindo o destino. Usado SO apos a variante do C10 sem supplemental logging, cujo redo nao e aplicavel pelo
# Replicat (OGG-30426: chave ausente no trail). Uso: scripts/gg/reparar-por-toque.sh CSV_GERADOR
set -euo pipefail
CSV=${1:?CSV do gerador}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
python3 -I - "$CSV" > "$tmp" <<'PY'
import csv, sys
por = {}
for row in list(csv.reader(open(sys.argv[1])))[1:]:
    por.setdefault(row[1].lower(), set()).add(int(row[2]))
print("set feedback off\nwhenever sqlerror exit failure")
for t, cods in sorted(por.items()):
    cods = sorted(cods)
    for i in range(0, len(cods), 900):
        lote = cods[i:i+900]
        linhas = [",".join(map(str, lote[j:j+100])) for j in range(0, len(lote), 100)]  # SQL*Plus: linha fisica < 5000 caracteres
        print(f"update {t} set excluido = excluido where codigo in (\n" + ",\n".join(linhas) + ");\ncommit;")
print("exit")
for t, c in sorted(por.items()):
    print(f"-- {t}: {len(c)} chaves", file=sys.stderr)
PY
"$ROOT/scripts/gg/q.sh" legado < "$tmp"
echo "toque aplicado ($(grep -c '^update' "$tmp") comandos)"
