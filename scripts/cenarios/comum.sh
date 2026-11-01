#!/usr/bin/env bash
# All scenarios share one lifecycle and one evidence/criteria implementation.
executar_cenario() {
  local biblioteca
  biblioteca=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  python3 "$biblioteca/executor.py" "$1"
}
