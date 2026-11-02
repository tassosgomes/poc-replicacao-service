#!/usr/bin/env python3
"""Local coordinator: only owned poc-* containers are mutated on shared hosts."""
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
from datetime import datetime, timezone

from analise import analyze, criteria, generator_summary, instant

ROOT = Path(__file__).resolve().parents[2]
HOSTS = ("desenv-server", "infra-server")
IMAGE = "eclipse-temurin:25-jre"
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2"]


def utc():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def number(key, default, minimum=1):
    value = int(os.environ.get(key, default))
    if value < minimum:
        raise ValueError(f"{key} deve ser >= {minimum}")
    return value


def command(args, data=None, timeout=180):
    return subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          check=True, timeout=timeout).stdout


def remote(host, args, data=None, timeout=180):
    return command(SSH + [host, shlex.join(args)], data, timeout)


def shell_function(name):
    return command(["bash", "-c", 'source "$1/scripts/comum.sh"; "$2"', "bash", str(ROOT), name]).decode().strip()


def sql(host, container, user, query):
    script = ("whenever sqlerror exit failure\nwhenever oserror exit failure\n"
              "set heading off feedback off pagesize 0 linesize 200 trimspool on verify off\n" + query + "\nexit\n")
    output = remote(host, ["docker", "exec", "-i", container, "sqlplus", "-s",
                           f"{user}/{user}@localhost:1521/FREEPDB1"], script.encode()).decode().strip()
    if re.search(r"(^|\n)(ORA-|SP2-|ERROR)", output):
        raise RuntimeError("consulta SQL falhou")
    return output


def pending():
    return int(sql("desenv-server", "poc-legado", "bdad", "select count(*) from evento_replicacao;"))


def inventory(host):
    listed = remote(host, ["docker", "ps", "-a", "--format", "{{.ID}} {{.Names}}"]).decode().splitlines()
    # docker cp can lock its own stopped container. The watchdog inspects existing services,
    # not a container whose artifact is being prepared or transferred by this runner.
    ids = [line.split()[0] for line in listed if not line.split()[1].startswith("poc-")
           or line.split()[1] in ("poc-legado", "poc-novo")]
    # Explicit allowlist: never retrieve Config.Env or docker-inspect's full sensitive document.
    template = ('{"nome":{{json .Name}},"id":{{json .Id}},"imagem":{{json .Config.Image}},'
                '"imagem_id":{{json .Image}},"cpu":{{.HostConfig.NanoCpus}},"memoria":{{.HostConfig.Memory}},'
                '"running":{{.State.Running}},"inicio":{{json .State.StartedAt}},'
                '"reinicios":{{.RestartCount}},"saudavel":{{if index .State "Health"}}{{json .State.Health.Status}}{{else}}null{{end}}}')
    containers = []
    if ids:
        output = remote(host, ["docker", "inspect", "--format", template] + ids).decode()
        containers = [json.loads(line) for line in output.splitlines()]
        for container in containers:
            container["nome"] = container["nome"].lstrip("/")
    load = float(remote(host, ["cat", "/proc/loadavg"]).decode().split()[0])
    return {"host": host, "carga_media_1m": load, "containers": containers}


def unavailable(before, after):
    current = {c["id"]: c for c in after["containers"]}
    for old in before["containers"]:
        if old["nome"].startswith("poc-") or not old["running"]:
            continue
        new = current.get(old["id"])
        if not new or not new["running"] or new["reinicios"] != old["reinicios"] or new["inicio"] != old["inicio"]:
            return old["nome"]
        if old["saudavel"] == "healthy" and new["saudavel"] != "healthy":
            return old["nome"]
    return None


class InvalidRun(Exception):
    pass


class Runner:
    def __init__(self, scenario):
        self.scenario = scenario
        self.execution = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + f"-{os.getpid()}-{time.monotonic_ns():x}"
        self.base = Path(os.environ.get("RESULTADOS_BASE", ROOT / "resultados")) / scenario
        self.directory = self.base / self.execution
        self.directory.mkdir(parents=True)
        self.started = utc()
        self.jar = Path(os.environ.get("POC_JAR", ROOT / "poc/target/poc-replicacao-service-0.0.1-SNAPSHOT.jar"))
        self.jar_sha = None
        self.image = None
        self.cached_hosts = set()
        self.containers = []
        self.phases = []
        self.special = {}
        self.clocks = []
        self.loads = []
        self.baseline = []
        self.environment = {}
        self.integrity = {}
        self.measures = {}
        self.reasons = []
        self.next_watch = 0
        self.next_clock = 0
        self.next_resources = 0
        self.worker = None
        self.workers = []
        self.cleaned = False
        self.closing = False
        self.interval = number("VIGILANCIA_SEG", 30)
        self.drain_timeout = number("DRENAGEM_TIMEOUT_SEG", 180)
        self.duration = number("DURACAO_SEG", 600)
        self.step_duration = number("DEGRAU_SEG", 300)
        self.rate = number("TAXA", 1000)
        self.steps = [int(v) for v in os.environ.get("C3_TAXAS", "500,1000,2000,4000").split(",")]
        if not self.steps or any(v <= 0 for v in self.steps) or self.steps != sorted(set(self.steps)):
            raise ValueError("C3_TAXAS deve ser uma escada crescente de inteiros positivos")
        self.legacy_address = None

    def clock(self, label):
        delta, uncertainty = map(int, shell_function("medir_relogios").split())
        sample = {"etapa": label, "ts": utc(), "skew_ms": delta / 1e6, "incerteza_ms": uncertainty / 1e6}
        self.clocks.append(sample)
        self.next_clock = time.monotonic() + 60
        if abs(delta) > 100_000_000:
            raise InvalidRun("RELOGIO")

    def watch(self, label="durante", force=False):
        now = time.monotonic()
        if force or now >= self.next_watch:
            observations = [inventory(h) for h in HOSTS]
            self.loads.append({"etapa": label, "ts": utc(), "hosts": observations})
            self.next_watch = now + self.interval
            for before, after in zip(self.baseline, observations):
                missing = unavailable(before, after)
                if missing:
                    self.special["servico_indisponivel"] = {"host": before["host"], "nome": missing}
                    raise InvalidRun("ABORTADO_SERVICO_EXISTENTE")
        if self.worker and now >= self.next_resources:
            self.resources()
            self.next_resources = time.monotonic() + 30
        if now >= self.next_clock:
            self.clock("durante")
        for worker in self.workers:
            if worker["process"].poll() is not None:
                raise RuntimeError("worker encerrou durante o cenario")

    def wait_until(self, predicate, timeout, message):
        deadline = time.monotonic() + timeout
        while True:
            self.watch()
            if predicate():
                return
            if time.monotonic() >= deadline:
                raise RuntimeError(message)
            time.sleep(.25)

    def pause(self, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.watch()
            time.sleep(min(.25, max(0, deadline - time.monotonic())))

    def spawn(self, role, host, options):
        self.prepare_image(host)
        name = f"poc-{role}-{self.execution.lower()}-{len(self.containers)}"
        args = ["docker", "create", "--name", name, "--network", "poc-rede", "--cpus=2",
                "--memory=1g", "--memory-swap=1g", "-e", "RESULTADOS_DIR=/resultados"]
        urls = {"LEGADO_URL": "jdbc:oracle:thin:@//poc-legado:1521/FREEPDB1" if host == "desenv-server"
                else f"jdbc:oracle:thin:@//{self.legacy_address}:1521/FREEPDB1",
                "NOVO_URL": "jdbc:oracle:thin:@//poc-novo:1521/FREEPDB1"}
        for key, value in (urls | options).items():
            args += ["-e", f"{key}={str(value).lower() if isinstance(value, bool) else value}"]
        args += [self.image, "java", "-Xmx512m", "-jar", "/app.jar", role]
        entry = {"name": name, "host": host, "role": role, "process": None, "collected": False}
        self.containers.append(entry)
        remote(host, args)
        identity = remote(host, ["docker", "inspect", "--format", "{{.Image}}", name]).decode().strip()
        self.environment.setdefault("processos", []).append({"host": host, "nome": name, "imagem": self.image,
                                                            "imagem_id": identity, "cpu": 2, "memoria": 1073741824})
        logfile = self.directory / (name + ".log")
        with logfile.open("wb") as output:
            entry["process"] = subprocess.Popen(SSH + [host, shlex.join(["docker", "start", "-a", name])],
                                                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
        entry["log"] = logfile
        return entry

    def prepare_image(self, host):
        if host in self.cached_hosts:
            return
        try:
            remote(host, ["docker", "image", "inspect", "--format", "{{.Id}}", self.image])
        except subprocess.CalledProcessError:
            name = f"poc-artefato-{self.execution.lower()}"
            entry = {"name": name, "host": host, "role": "artefato", "process": None, "collected": True}
            self.containers.append(entry)
            remote(host, ["docker", "create", "--name", name, "--network", "none", "--cpus=2", "--memory=1g", IMAGE, "true"])
            # A cached image belongs to the PoC and is content addressed; no host installation.
            with tempfile.TemporaryFile() as archive, tempfile.TemporaryFile() as output:
                with tarfile.open(fileobj=archive, mode="w") as stream:
                    stream.add(self.jar, arcname="app.jar")
                archive.seek(0)
                process = subprocess.Popen(SSH + [host, shlex.join(["docker", "cp", "-", name + ":/"])],
                                           stdin=archive, stdout=output, stderr=output)
                try:
                    self.wait_until(lambda: process.poll() is not None, 300, "transferencia do jar excedeu limite")
                    if process.wait() != 0:
                        raise RuntimeError("transferencia do jar falhou")
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()
            remote(host, ["docker", "commit", name, self.image])
        self.cached_hosts.add(host)

    def collect(self, entry):
        if entry["collected"]:
            return
        destination = self.directory / entry["name"].removeprefix("poc-")
        destination.mkdir(exist_ok=True)
        with tempfile.TemporaryFile() as archive, tempfile.TemporaryFile() as errors:
            process = subprocess.Popen(SSH + [entry["host"], shlex.join(["docker", "cp", entry["name"] + ":/resultados/.", "-"])],
                                       stdin=subprocess.DEVNULL, stdout=archive, stderr=errors)
            try:
                if self.closing:
                    process.wait(timeout=180)
                else:
                    self.wait_until(lambda: process.poll() is not None, 180, "coleta de evidencias excedeu limite")
                if process.wait() != 0:
                    raise RuntimeError("coleta de evidencias falhou")
                archive.seek(0)
                with tarfile.open(fileobj=archive) as stream:
                    stream.extractall(destination, filter="data")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
        entry["collected"] = True
        return destination

    def finish(self, entry, stop=False):
        process = entry["process"]
        if stop and entry in self.workers:
            self.workers.remove(entry)
        if stop and process.poll() is None:
            remote(entry["host"], ["docker", "stop", "-t", "30", entry["name"]], timeout=60)
        process.wait(timeout=60)
        state = remote(entry["host"], ["docker", "inspect", "--format", "{{.State.ExitCode}}", entry["name"]]).decode().strip()
        if not stop and (process.returncode != 0 or state != "0"):
            raise RuntimeError(f"{entry['role']} falhou (container exit {state})")
        return self.collect(entry)

    def create_worker(self, virtual=True, options=None):
        worker_id = f"v11-{self.execution}-{len(self.containers)}"
        entry = self.spawn("worker", "infra-server", {"WORKER_ID": worker_id, "VIRTUAL_THREADS": virtual,
                                                      "EVENTOS_CSV": True} | (options or {}))
        entry["worker_id"] = worker_id
        self.workers.append(entry)
        return entry

    def start_worker(self, virtual=True):
        self.worker = self.create_worker(virtual)
        worker_id = self.worker["worker_id"]
        def ready():
            count = sql("infra-server", "poc-novo", "replica",
                        f"select count(*) from lease_particao where dono='{worker_id}' and expira_em>sys_extract_utc(systimestamp);")
            return count == "48"
        self.wait_until(ready, 90, "worker nao assumiu as 48 particoes")

    def prepare_replica(self):
        """Optional (PREPARAR_REPLICA=true): bring a replica left incomplete by earlier tests to a
        consistent state with the idempotent, version-guarded initial load. Nothing is deleted and
        UUIDs are preserved; the final complete reconciliation still decides the integrity."""
        if os.environ.get("PREPARAR_REPLICA", "false").lower() != "true":
            return
        started = time.monotonic()
        entry = self.spawn("carga-inicial", "infra-server", {"THREADS": number("PREPARAR_THREADS", 4)})
        entry["collected"] = True  # the initial load writes no result directory
        timeout = number("PREPARAR_TIMEOUT_SEG", 3600)
        self.wait_until(lambda: entry["process"].poll() is not None, timeout, "carga inicial de preparo excedeu limite")
        state = remote(entry["host"], ["docker", "inspect", "--format", "{{.State.ExitCode}}", entry["name"]]).decode().strip()
        if entry["process"].returncode != 0 or state != "0":
            raise RuntimeError("carga inicial de preparo falhou")
        self.special["preparo_replica"] = {"metodo": "carga-inicial idempotente", "log": entry["log"].name,
                                           "duracao_s": round(time.monotonic() - started, 1)}
        # The replica was changed on purpose; the clock/watchdog state must not age during it.
        self.clock("apos_preparo")

    def phase(self, group, rate, duration, virtual=True, options=None):
        generator = self.spawn("gerador", "desenv-server", {"TAXA": rate, "DURACAO_SEG": duration,
                               "THREADS": number("THREADS", 8), "SEMENTE": number("SEMENTE", 1),
                               "PCT_INSERT": number("PCT_INSERT", 5, 0), "PCT_DELETE": number("PCT_DELETE", 1, 0),
                               "HOT_PCT": number("HOT_PCT", 10, 0)} | (options or {}))
        return {"entry": generator, "grupo": group, "virtual_threads": virtual, "duration": duration}

    def finish_phase(self, phase):
        entry = phase.pop("entry")
        duration = phase.pop("duration")
        self.wait_until(lambda: entry["process"].poll() is not None, duration + 120, "gerador nao encerrou")
        self.finish(entry)
        log = entry["log"].read_text()
        result = generator_summary(log)
        start = re.search(r"inicio_carga=(\S+)", log)
        end = re.search(r"fim_carga=(\S+)", log)
        if not start or not end:
            raise RuntimeError("janela do gerador ausente")
        phase.update(result, inicio=instant(start[1]), fim=instant(end[1]), log=entry["log"].name)
        phase["validade"] = "VALIDO" if result["valido"] else ("GERADOR_SATURADO" if phase["grupo"] == "c3" else "INVALIDO")
        self.phases.append(phase)
        if not result["valido"] and phase["grupo"] != "c3":
            self.reasons.append("TAXA")
        return result["valido"]

    def staircase(self, virtual=True):
        for rate in self.steps:
            if not self.finish_phase(self.phase("c3", rate, self.step_duration, virtual)):
                break

    def workload(self):
        if self.scenario == "c3":
            self.staircase()
        elif self.scenario == "c9":
            for virtual in (True, False):
                if not virtual:
                    self.wait_until(lambda: pending() == 0, self.drain_timeout, "drenagem entre modos excedeu limite")
                    old = self.worker
                    self.worker = None
                    self.finish(old, stop=True)
                    self.start_worker(False)
                self.finish_phase(self.phase("c2", self.rate, self.duration, virtual))
                self.staircase(virtual)
        elif self.scenario in ("c4", "c5"):
            delay = number("RAJADA_APOS_SEG", 120) if self.scenario == "c4" else number("TX_APOS_SEG", 5, 0)
            hold = number("TX_ABERTA_SEG", 30)
            recovery = number("OBSERVACAO_RECUPERACAO_SEG", 125)
            # Background stays alive through observation of recovery/long transaction completion.
            minimum = delay + (recovery + 30 if self.scenario == "c4" else hold + 30)
            background = self.phase(self.scenario, self.rate, max(self.duration, minimum))
            self.pause(delay)
            if self.scenario == "c4":
                extra = {"RAJADA_LINHAS": number("RAJADA_LINHAS", 50000)}
            else:
                extra = {"TX_ABERTA_SEG": hold}
            generator = self.spawn("gerador", "desenv-server", extra)
            self.wait_until(lambda: generator["process"].poll() is not None, hold + 180, "operacao especial nao encerrou")
            destination = self.finish(generator)
            log = generator["log"].read_text()
            if self.scenario == "c4":
                first = next(destination.glob("gerador-linhas-*.csv"))
                with first.open() as stream:
                    rows = csv.DictReader(stream)
                    commit = next(rows)["instante"]
                self.special["rajada_commit"] = instant(commit)
                self.special["rajada_linhas"] = generator_summary(log)["linhas"]
                self.pause(recovery)
            else:
                match = re.search(r"evento_longo=(\d+) criado_em=(\S+) commit=(\S+)", log)
                if not match:
                    raise RuntimeError("identidade da transacao longa ausente")
                self.special.update(evento_longo=match[1], criado=instant(match[2]), commit=instant(match[3]))
            self.finish_phase(background)
        else:
            self.finish_phase(self.phase("c2", self.rate, self.duration))

    def snapshot(self, label):
        destination = self.directory / "snapshots" / label
        with (self.directory / f"snapshot-{label}.log").open("wb") as output:
            process = subprocess.Popen(["bash", str(ROOT / "scripts/snapshot-ids.sh"), str(destination)],
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                self.wait_until(lambda: process.poll() is not None, 300, "snapshot excedeu limite")
                if process.wait() != 0:
                    raise RuntimeError("snapshot falhou")
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
        self.integrity["snapshot_" + label] = str(destination.relative_to(self.directory))

    def resources(self):
        # One bounded sample every watchdog interval; original V-05 collector retained for other users.
        path = self.directory / "recursos.csv"
        with path.open("a") as stream:
            if path.stat().st_size == 0:
                stream.write("ts,container,cpu_percent,memoria,sessoes_ativas\n")
            for host, container in zip(HOSTS, ("poc-legado", "poc-novo")):
                stats = remote(host, ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}};{{.MemUsage}}", container]).decode().strip()
                sessions = remote(host, ["docker", "exec", "-i", container, "sqlplus", "-s", "/ as sysdba"],
                                  b"whenever sqlerror exit failure\nset heading off feedback off pages 0\nselect count(*) from v$session where status='ACTIVE' and type='USER';\nexit\n").decode().strip()
                if not sessions.isdigit() or ";" not in stats:
                    raise RuntimeError("amostra de recursos invalida")
                stream.write(f"{utc()},{container},{stats.replace(';', ',')},{sessions}\n")
            for entry in self.containers:
                if entry["process"] and entry["process"].poll() is None:
                    stats = remote(entry["host"], ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}};{{.MemUsage}}", entry["name"]]).decode().strip()
                    stream.write(f"{utc()},{entry['name']},{stats.replace(';', ',')},0\n")

    def setup(self):
        shell_function("pre_requisitos_rapidos")
        if not self.jar.is_file():
            raise RuntimeError("jar ausente; execute ./mvnw -B -q package (gera poc/target/...)")
        self.jar_sha = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.image = "poc-cenarios:" + self.jar_sha
        self.baseline = [inventory(h) for h in HOSTS]
        self.loads.append({"etapa": "antes", "ts": utc(), "hosts": self.baseline})
        self.environment = {"hosts": list(HOSTS), "containers": [c for h in self.baseline for c in h["containers"] if c["nome"].startswith("poc-")],
                            "imagem_aplicacao": self.image, "imagem_base": IMAGE, "jar": self.jar.name,
                            "jar_sha256": self.jar_sha,
                            "limites_aplicacao": {"cpu": 2, "memoria": 1073741824}}
        # Existing PoC processes would change worker quota and invalidate the one-worker scenario.
        for host in self.baseline:
            active = remote(host["host"], ["docker", "ps", "--filter", "name=^/poc-", "--format", "{{.Names}}"])
            unexpected = [name for name in active.decode().splitlines() if name not in ("poc-legado", "poc-novo")]
            if unexpected:
                raise RuntimeError("processos PoC preexistentes; encerre seus cenarios antes de executar")
        config = command(["ssh", "-G", "desenv-server"]).decode()
        self.legacy_address = re.search(r"^hostname (.+)$", config, re.M)[1]
        self.clock("inicio")
        self.next_watch = time.monotonic() + self.interval

    def reconciliation_options(self):
        return {}

    def execute(self):
        self.setup()
        self.prepare_replica()
        self.start_worker()
        self.wait_until(lambda: pending() == 0, self.drain_timeout, "pendentes iniciais nao zeraram")
        self.integrity["pendentes_inicial"] = pending()
        self.snapshot("antes")
        self.next_resources = 0
        self.workload()
        self.wait_until(lambda: pending() == 0, self.drain_timeout, "pendentes finais nao zeraram")
        self.integrity["pendentes_final"] = pending()
        recon = self.spawn("reconciliacao", "infra-server", {"MODO": "completo", "DRY_RUN": True} | self.reconciliation_options())
        self.wait_until(lambda: recon["process"].poll() is not None, 300, "reconciliacao excedeu limite")
        # Reconciliation has no result directory; the complete stdout is its evidence.
        process = recon["process"]
        process.wait(timeout=30)
        if process.returncode != 0:
            raise RuntimeError("reconciliacao falhou")
        state = remote(recon["host"], ["docker", "inspect", "--format", "{{.State.ExitCode}}", recon["name"]]).decode().strip()
        if state != "0":
            raise RuntimeError("reconciliacao falhou no container")
        summary = re.search(r"^total=(\d+) tempo_ms=\d+ hash=", recon["log"].read_text(), re.M)
        if not summary:
            raise RuntimeError("resumo da reconciliacao ausente")
        self.integrity.update(divergencias=int(summary[1]), reconciliacao=recon["log"].name)
        recon["collected"] = True
        self.snapshot("depois")
        compared = subprocess.run(["bash", str(ROOT / "scripts/comparar-snapshots.sh"),
                                   str(self.directory / "snapshots/antes"), str(self.directory / "snapshots/depois")],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        if compared.returncode not in (0, 1):
            raise RuntimeError("comparacao de snapshots falhou")
        (self.directory / "diferencas.csv").write_bytes(compared.stdout)
        self.integrity.update(uuids_alterados=max(0, len(compared.stdout.splitlines()) - 1), diferencas="diferencas.csv")
        self.resources()
        self.watch("depois", force=True)
        self.clock("fim")

    def cleanup(self):
        errors = []
        self.closing = True
        self.worker = None
        self.workers.clear()
        # Stop every generator first: evidence transfer must not delay the safety abort.
        for host in HOSTS:
            active = [e["name"] for e in self.containers if e["host"] == host and e["role"] == "gerador"
                      and e["process"] and e["process"].poll() is None]
            if active:
                try:
                    remote(host, ["docker", "stop", "-t", "3"] + active, timeout=30)
                except subprocess.SubprocessError:
                    errors.append("parada_gerador")
        for entry in reversed(self.containers):
            try:
                process = entry["process"]
                if process and process.poll() is None:
                    remote(entry["host"], ["docker", "stop", "-t", "30" if entry["role"] == "worker" else "3", entry["name"]], timeout=60)
                if process:
                    try:
                        process.wait(timeout=45)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                        raise RuntimeError("SSH nao encerrou apos stop")
                if process and not entry["collected"] and entry["role"] in ("gerador", "worker"):
                    self.collect(entry)
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                errors.append(type(error).__name__)
            finally:
                try:
                    remote(entry["host"], ["docker", "rm", "-f", entry["name"]])
                except subprocess.SubprocessError:
                    errors.append("remocao_container")
                process = entry["process"]
                if process and process.poll() is None:
                    process.kill()
                    process.wait()
        self.cleaned = True
        if errors:
            self.reasons.append("ERRO_LIMPEZA")

    def evaluate(self):
        self.measures = analyze(self.directory, self.phases, self.special)
        if self.scenario == "c2" and self.phases:
            self.measures["p95_ms"] = self.phases[0]["p95_ms"]
        # C8 deliberately produces no events when capture is disabled and delta misses its window.
        # Its evidence is the generator row log plus complete comparisons, even with zero latencies.
        if self.measures["amostras"] == 0 or (self.scenario not in ("c1", "c8") and self.measures["eventos_medidos"] == 0):
            self.reasons.append("EVIDENCIA_INSUFICIENTE")
        if not self.measures["relogios_validos"]:
            self.reasons.append("RELOGIO")
        return criteria(self.scenario, self.phases, self.measures, self.integrity, self.special)

    def save(self):
        checks = self.evaluate()
        if any(c["veredito"] == "INVALIDO" for c in checks):
            missing_2000 = self.scenario in ("c3", "c9") and not any(p["grupo"] == "c3" and p["valido"] and p["alvo"] == 2000 for p in self.phases)
            self.reasons.append("TAXA" if missing_2000 else "EVIDENCIA_INSUFICIENTE")
        if any(c["veredito"] == "NAO_EXECUTADO" for c in checks):
            self.reasons.append("EVIDENCIA_INSUFICIENTE")
        invalid = bool(self.reasons)
        if invalid:
            for check in checks:
                if check["veredito"] not in ("NAO_EXECUTADO", "SEM_CRITERIO"):
                    check["veredito"] = "INVALIDO"
        verdict = "INVALIDO" if invalid else "REPROVADO" if any(c["veredito"] == "REPROVADO" for c in checks) else "APROVADO"
        if verdict == "APROVADO" and self.scenario == "c10" and not self.special.get("limite_ativo", True):
            verdict = "SEM_CRITERIO"
        data = {"cenario": self.scenario, "execucao": self.execution, "inicio": self.started, "fim": utc(),
                "diretorio": os.path.relpath(self.directory, ROOT), "ambiente": self.environment,
                "validade": {"estado": "INVALIDO" if invalid else "VALIDO", "motivos": sorted(set(self.reasons))},
                "veredito": verdict, "skew_ms": {r["etapa"]: r["skew_ms"] for r in self.clocks},
                "skew_incerteza_ms": {r["etapa"]: r["incerteza_ms"] for r in self.clocks}, "relogios": self.clocks,
                "carga_servidores": self.loads, "criterios": checks, "integridade": self.integrity,
                "medidas": self.measures, "degraus": self.phases, "operacao_especial": self.special,
                "parametros": {"taxa": self.rate, "duracao_seg": self.duration, "degrau_seg": self.step_duration,
                               "vigilancia_seg": self.interval, "c3_taxas": self.steps}}
        if self.scenario == "c9":
            with (self.directory / "comparacao.csv").open("w") as stream:
                writer = csv.writer(stream)
                writer.writerow(["virtual_threads", "cenario", "alvo", "taxa_ev_s", "p95_ms", "validade"])
                for phase in self.phases:
                    writer.writerow([phase["virtual_threads"], phase["grupo"], phase["alvo"], phase["taxa"], phase.get("p95_ms"), phase["validade"]])
        payload = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
        (self.directory / "meta.json").write_text(payload)
        temporary = self.base / f".meta-{self.execution}.json"
        temporary.write_text(payload)
        temporary.replace(self.base / "meta.json")
        print(f"{self.scenario}: {verdict}; {self.directory / 'meta.json'}", flush=True)
        return 0 if verdict in ("APROVADO", "SEM_CRITERIO") else 1


def main():
    if sys.argv[1] in ("c1", "c10"):
        from carga_custo import LoadCostRunner
        runner = LoadCostRunner(sys.argv[1])
    elif sys.argv[1] in ("c6", "c7", "c8"):
        from falhas import FailureRunner
        runner = FailureRunner(sys.argv[1])
    else:
        runner = Runner(sys.argv[1])
    def interrupted(signum, _frame):
        raise InvalidRun("INTERROMPIDO")
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, interrupted)
    status = 0
    try:
        runner.execute()
    except InvalidRun as error:
        runner.reasons.append(str(error))
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        # Do not serialize command arguments/stderr: a future transport could contain secrets.
        runner.reasons.append("ERRO_OPERACIONAL")
        (runner.directory / "erro.txt").write_text(type(error).__name__ + ": " +
            (str(error) if isinstance(error, (ValueError, RuntimeError)) else "falha de comando; verifique o ambiente") + "\n")
        status = 2
    finally:
        runner.cleanup()
        try:
            runner.clock("fim")
            runner.loads.append({"etapa": "depois", "ts": utc(), "hosts": [inventory(h) for h in HOSTS]})
        except InvalidRun as error:
            runner.reasons.append(str(error))
        except (OSError, ValueError, subprocess.SubprocessError):
            runner.reasons.append("ERRO_OPERACIONAL")
    result = runner.save()
    return status or result


if __name__ == "__main__":
    sys.modules["executor"] = sys.modules[__name__]
    sys.exit(main())
