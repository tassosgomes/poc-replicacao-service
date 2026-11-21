#!/usr/bin/env bash
# Carga inicial do destino (kf-novo) com o CargaInicial do jar da PoC (o mesmo do C1 do lab Java: MERGE guardado por versao,
# UUIDv7 gerado pelo worker, faixas de 10.000 codigos, THREADS=4) e alinhada ao conector:
#   1. o conector ja esta em streaming (registrar-conector.sh), com SCN_INICIAL S0 fixado, e os workers ainda nao rodam;
#   2. SCN_CARGA = current_scn do legado logo antes da carga (>= S0);
#   3. CargaInicial le o legado (estado corrente, linha a linha) e faz MERGE no destino;
#   4. depois os workers aplicam o topico desde o offset 0 (inclui eventos entre S0 e a carga e durante ela).
# Correto sem perda nem duplicidade: todo commit posterior a S0 esta no topico; uma linha lida pela carga numa versao v so e
# sobrescrita por evento de versao > v (guarda); evento repetido (versao <= a do destino) e descartado; delete fisico convertido
# vira excluido=1 e nao e desfeito pela carga (versao do delete > ultima versao lida).
# Uso: scripts/kf/carga-inicial.sh [DIR_SAIDA]   Saida: JSON do tempo em stdout (linhas, tempos pelo relogio do desenv-server).
set -euo pipefail
source "$(dirname "$0")/comum-kf.sh"
OUT=${1:-$(mktemp -d)}; THREADS=${KF_CARGA_THREADS:-4}
S0=${KF_SCN_INICIAL:-$(offset_conector | jq -r ".snapshot_scn // empty" || true)}
SCN=$(scn_legado)
[[ -z $S0 || $SCN -ge $S0 ]] || { echo "SCN da carga ($SCN) anterior ao SCN inicial do conector ($S0)" >&2; exit 2; }
T0=$(agora)
"$(dirname "$0")/poc.sh" infra-server carga-inicial THREADS="$THREADS" > "$OUT/carga-inicial.log" 2>&1 || { cat "$OUT/carga-inicial.log" >&2; exit 2; }
T1=$(agora)
tot=$(grep -o 'tempo_total_s=[0-9.]*' "$OUT/carga-inicial.log" | cut -d= -f2)
jq -nc --argjson s0 "${S0:-null}" --argjson scn "$SCN" --argjson t "$(dur "$T0" "$T1")" --argjson tj "${tot:-null}" --argjson th "$THREADS" \
  '{scn_inicial_conector:$s0, scn_carga:$scn, threads:$th, tempo_s_relogio_desenv:$t, tempo_total_s_jar:$tj}'
