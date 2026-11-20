# /// script
# requires-python = ">=3.12"
# dependencies = ["fastapi", "uvicorn", "oracledb"]
# ///
"""Demo da replicação BDAD -> MAESTRO (GoldenGate) na ótica do usuário.

A UI ESCREVE direto no legado (simula o Delphi) e só LÊ da réplica.
Rodar: uv run demo-ui/app.py   (abre em http://localhost:8099)
"""
import os
import threading
import time
from datetime import datetime
from pathlib import Path

import oracledb
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

env = os.environ.get
CFG = {
    "legado": dict(dsn=env("DEMO_LEGADO_DSN", "192.168.0.5:1523/FREEPDB1"),
                   user=env("DEMO_LEGADO_USER", "bdad"), password=env("DEMO_LEGADO_SENHA", "bdad")),
    "replica": dict(dsn=env("DEMO_REPLICA_DSN", "192.168.0.11:1524/FREEPDB1"),
                    user=env("DEMO_REPLICA_USER", "replica"), password=env("DEMO_REPLICA_SENHA", "replica")),
}
PORTA = int(env("DEMO_PORTA", "8099"))
LIMITE = 50

# slug da URL -> definição da tabela
TABELAS = {
    "titulares": dict(nome="TITULAR", seq="seq_titular", campos=["nome", "tipo", "documento", "situacao"], edita="nome"),
    "obras": dict(nome="OBRA_MUSICAL", seq="seq_obra_musical", campos=["titulo", "iswc", "genero", "situacao"], edita="titulo"),
    "fonogramas": dict(nome="FONOGRAMA", seq="seq_fonograma", campos=["obra_codigo", "titulo", "isrc", "duracao_seg"], edita="titulo"),
}
POR_NOME = {t["nome"]: t for t in TABELAS.values()}

_pools: dict[str, oracledb.ConnectionPool] = {}
_lock = threading.Lock()


def pool(lado: str) -> oracledb.ConnectionPool:
    with _lock:
        if lado not in _pools:
            c = CFG[lado]
            _pools[lado] = oracledb.create_pool(
                user=c["user"], password=c["password"], dsn=c["dsn"], min=1, max=3, increment=1,
                getmode=oracledb.POOL_GETMODE_TIMEDWAIT, wait_timeout=5000, tcp_connect_timeout=5)
        return _pools[lado]


def conectar(lado: str):
    """Conexão do pool; com timeout de chamada para nunca travar a UI."""
    try:
        con = pool(lado).acquire()
    except Exception as e:
        raise HTTPException(503, f"Sem conexão com {'a origem (legado)' if lado == 'legado' else 'a réplica'}: {e}")
    con.call_timeout = 10000
    return con


def linhas(cur) -> list[dict]:
    cols = [d[0].lower() for d in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def iso(v):
    return v.isoformat(sep=" ", timespec="milliseconds") if isinstance(v, datetime) else v


def uuid_fmt(h: str | None):
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}".lower() if h else None


def norm(v):
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)) or hasattr(v, "as_tuple"):
        return float(v)
    return str(v)


# ---------- leitura ----------

def ler_origem(slug: str, q: str | None) -> list[dict]:
    t = TABELAS[slug]
    cols = ", ".join(["codigo", *t["campos"], "excluido", "updated_at"])
    if q:
        if not q.strip().isdigit():
            raise HTTPException(400, "A busca é pelo código (número).")
        sql = f"select {cols} from {t['nome']} where codigo = :c"
        binds = {"c": int(q)}
    else:
        # updated_at is not null permite usar o índice ix_*_updated em ordem decrescente
        sql = (f"select * from (select {cols} from {t['nome']} where updated_at is not null "
               f"order by updated_at desc) where rownum <= {LIMITE}")
        binds = {}
    with conectar("legado") as con, con.cursor() as cur:
        cur.execute(sql, binds)
        return linhas(cur)


def ler_replica(slug: str, codigos: list[int]) -> dict[int, dict]:
    t = TABELAS[slug]
    cols = ", ".join(["rawtohex(id) as id", "codigo", *t["campos"], "excluido",
                      "origem_updated_at", "replicado_em", "origem_commit_ts"])
    out: dict[int, dict] = {}
    if not codigos:
        return out
    with conectar("replica") as con, con.cursor() as cur:
        for i in range(0, len(codigos), 100):
            lote = codigos[i:i + 100]
            ph = ", ".join(f":{n + 1}" for n in range(len(lote)))
            cur.execute(f"select {cols} from {t['nome']} where codigo in ({ph})", lote)
            for r in linhas(cur):
                out[int(r["codigo"])] = r
    return out


def status_de(slug: str, o: dict | None, r: dict | None) -> str:
    """pendente | replicado | excluído | divergente (o=None: linha só existe na réplica)."""
    if r is None:
        return "pendente"
    if o is None:
        return "excluído" if norm(r["excluido"]) == 1 else "replicado"
    if r["origem_updated_at"] is None or r["origem_updated_at"] < o["updated_at"]:
        return "pendente"
    campos = [*TABELAS[slug]["campos"], "excluido"]
    if all(norm(o[c]) == norm(r[c]) for c in campos):
        return "excluído" if norm(r["excluido"]) == 1 else "replicado"
    return "divergente"


def montar(slug: str, o: dict | None, r: dict | None) -> dict:
    lat = None
    if o and r and r["replicado_em"] and o["updated_at"] and r["origem_updated_at"] and r["origem_updated_at"] >= o["updated_at"]:
        lat = round((r["replicado_em"] - o["updated_at"]).total_seconds(), 2)
    fmt = lambda d: None if d is None else {k: iso(v) for k, v in d.items()}  # noqa: E731
    return {"codigo": int((o or r)["codigo"]), "origem": fmt(o),
            "replica": fmt({**r, "id": uuid_fmt(r["id"])}) if r else None,
            "status": status_de(slug, o, r), "latencia": lat}


def tabela_lado_a_lado(slug: str, q: str | None):
    orig = ler_origem(slug, q)
    rep = ler_replica(slug, [int(o["codigo"]) for o in orig])
    return [montar(slug, o, rep.get(int(o["codigo"]))) for o in orig]


# ---------- escrita (somente legado) ----------

def cadastrar_obra(d: dict, commit: bool = True) -> dict:
    """Obra + titular opcional + fonogramas em UMA transação no legado.
    commit=False (uso interno de teste): executa tudo e faz ROLLBACK."""
    titulo = (d.get("titulo") or "").strip()
    fonos = [f for f in d.get("fonogramas", []) if (f.get("titulo") or "").strip()]
    if not titulo:
        raise HTTPException(400, "Informe o título da obra.")
    if not fonos:
        raise HTTPException(400, "Informe ao menos um fonograma com título.")
    tit = d.get("titular")
    if tit and not (tit.get("nome") or "").strip():
        tit = None
    if tit and tit.get("tipo") not in ("PF", "PJ"):
        raise HTTPException(400, "Tipo do titular deve ser PF ou PJ.")
    criadas: list[dict] = []
    con = conectar("legado")
    try:
        with con.cursor() as cur:
            def insere(tabela_nome, seq, cols: dict):
                cod = cur.var(int)
                nomes = ", ".join(["codigo", *cols, "excluido"])
                ph = ", ".join([f"{seq}.nextval", *[f":{k}" for k in cols], "0"])
                cur.execute(f"insert into {tabela_nome} ({nomes}) values ({ph}) returning codigo into :cod_out",
                            {**cols, "cod_out": cod})
                criadas.append({"tabela": tabela_nome, "codigo": int(cod.getvalue(0)[0])})
                return criadas[-1]["codigo"]

            obra = insere("OBRA_MUSICAL", "seq_obra_musical", dict(
                titulo=titulo, iswc=(d.get("iswc") or None), genero=(d.get("genero") or None),
                situacao=(d.get("situacao") or "ATIVA")))
            for f in fonos:
                insere("FONOGRAMA", "seq_fonograma", dict(
                    obra_codigo=obra, titulo=f["titulo"].strip(), isrc=(f.get("isrc") or None),
                    duracao_seg=(int(f["duracao_seg"]) if f.get("duracao_seg") not in (None, "") else None)))
            if tit:
                insere("TITULAR", "seq_titular", dict(
                    nome=tit["nome"].strip(), tipo=tit["tipo"], documento=(tit.get("documento") or None),
                    situacao="ATIVO"))
        if commit:
            con.commit()
        else:
            con.rollback()
    except oracledb.Error as e:
        con.rollback()
        raise HTTPException(400, f"Erro ao gravar no legado: {e}")
    finally:
        con.close()
    return {"criadas": criadas, "commit": commit}


def alterar(slug: str, codigo: int, acao: str, valor: str | None = None, commit: bool = True) -> dict:
    t = TABELAS[slug]
    if acao == "editar":
        if not (valor or "").strip():
            raise HTTPException(400, "Valor vazio.")
        sql, binds = f"update {t['nome']} set {t['edita']} = :v where codigo = :c", {"v": valor.strip(), "c": codigo}
    elif acao == "excluir-logico":
        sql, binds = f"update {t['nome']} set excluido = 1 where codigo = :c", {"c": codigo}
    else:
        sql, binds = f"delete from {t['nome']} where codigo = :c", {"c": codigo}
    con = conectar("legado")
    try:
        with con.cursor() as cur:
            try:
                cur.execute(sql, binds)
            except oracledb.IntegrityError as e:
                con.rollback()
                if "ORA-02292" in str(e):
                    raise HTTPException(409, "Não é possível excluir fisicamente: esta obra ainda tem fonogramas "
                                             "(chave estrangeira). Exclua os fonogramas antes.")
                raise HTTPException(409, f"Violação de integridade: {e}")
            if cur.rowcount == 0:
                con.rollback()
                raise HTTPException(404, "Linha não encontrada na origem.")
        con.commit() if commit else con.rollback()
    finally:
        con.close()
    return {"ok": True, "tabela": t["nome"], "codigo": codigo, "commit": commit}


# ---------- contagens e saúde (cache, nunca bloqueia) ----------
_cont = {"valores": {}, "em": 0.0, "rodando": False}


def _atualiza_contagens():
    try:
        v = {}
        for lado in ("legado", "replica"):
            try:
                con = pool(lado).acquire()
            except Exception:
                continue
            with con:
                con.call_timeout = 20000
                with con.cursor() as cur:
                    for slug, t in TABELAS.items():
                        cur.execute(f"select count(*) from {t['nome']}")
                        v[f"{lado}.{slug}"] = int(cur.fetchone()[0])
        if v:
            _cont["valores"] = v
            _cont["em"] = time.time()
    except Exception:
        pass
    finally:
        _cont["rodando"] = False


def contagens():
    if time.time() - _cont["em"] > 30 and not _cont["rodando"]:
        _cont["rodando"] = True
        threading.Thread(target=_atualiza_contagens, daemon=True).start()
    return {"valores": _cont["valores"], "idade_s": round(time.time() - _cont["em"]) if _cont["em"] else None}


def ping(lado: str) -> dict:
    t0 = time.perf_counter()
    try:
        with pool(lado).acquire() as con:
            con.call_timeout = 5000
            with con.cursor() as cur:
                cur.execute("select 1 from dual")
                cur.fetchone()
        return {"ok": True, "ms": round((time.perf_counter() - t0) * 1000)}
    except Exception as e:
        return {"ok": False, "erro": str(e)[:200]}


# ---------- API ----------
app = FastAPI(title="Demo replicação BDAD -> MAESTRO")
ESTATICO = Path(__file__).parent / "static"


class Edicao(BaseModel):
    valor: str


class Cadastro(BaseModel):
    titulo: str
    iswc: str | None = None
    genero: str | None = None
    situacao: str | None = "ATIVA"
    titular: dict | None = None
    fonogramas: list[dict]


class Itens(BaseModel):
    itens: list[dict]


def tab(slug: str):
    if slug not in TABELAS:
        raise HTTPException(404, "Tabela desconhecida.")
    return slug


@app.get("/")
def index():
    return FileResponse(ESTATICO / "index.html")


@app.get("/api/saude")
def saude():
    return {"legado": ping("legado"), "replica": ping("replica"), "contagens": contagens()}


@app.get("/api/linhas/{slug}")
def listar(slug: str, q: str | None = None):
    return {"linhas": tabela_lado_a_lado(tab(slug), q), "agora": iso(datetime.now())}


@app.put("/api/linhas/{slug}/{codigo}")
def editar(slug: str, codigo: int, body: Edicao):
    return alterar(tab(slug), codigo, "editar", body.valor)


@app.post("/api/linhas/{slug}/{codigo}/excluir-logico")
def excluir_logico(slug: str, codigo: int):
    return alterar(tab(slug), codigo, "excluir-logico")


@app.delete("/api/linhas/{slug}/{codigo}")
def excluir_fisico(slug: str, codigo: int):
    return alterar(tab(slug), codigo, "excluir-fisico")


@app.post("/api/cadastro-obra")
def cadastro_obra(body: Cadastro):
    return cadastrar_obra(body.model_dump())


@app.post("/api/acompanhar")
def acompanhar(body: Itens):
    """Estado na réplica de linhas já criadas: [{tabela: NOME, codigo}]."""
    saida = []
    por_tab: dict[str, list[int]] = {}
    for i in body.itens[:50]:
        if i.get("tabela") in POR_NOME:
            por_tab.setdefault(i["tabela"], []).append(int(i["codigo"]))
    for nome, cods in por_tab.items():
        slug = next(s for s, t in TABELAS.items() if t["nome"] == nome)
        rep = ler_replica(slug, cods)
        for c in cods:
            r = rep.get(c)
            saida.append({"tabela": nome, "codigo": c, "existe": r is not None,
                          "uuid": uuid_fmt(r["id"]) if r else None,
                          "excluido": norm(r["excluido"]) == 1 if r else None,
                          "replicado_em": iso(r["replicado_em"]) if r else None})
    return {"itens": saida}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORTA, log_level="warning")
