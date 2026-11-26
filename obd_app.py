"""
OBD2 / ELM327 - Leitor para Renault Clio 1.0 16V (D4D) 2005
Conexão: Bluetooth LE, USB/Bluetooth clássico (porta COM), Wi-Fi (TCP) ou Simulador.
Requer: Python 3.8+, pyserial e bleak (pip install -r requirements.txt)
"""
import asyncio
import csv
import math
import queue
import random
import re
import socket
import threading
import time
import tkinter as tk
from datetime import datetime
from tkinter import filedialog, messagebox, ttk

try:
    import serial
    import serial.tools.list_ports
except ImportError:  # o app ainda funciona em Wi-Fi/Simulador
    serial = None

try:
    from bleak import BleakClient, BleakScanner
except ImportError:  # sem suporte a Bluetooth LE
    BleakClient = BleakScanner = None

APP_TITLE = "OBD2 Clio 1.0 16V – ELM327"

# --------------------------------------------------------------------------
# Configurações do veículo (usadas no consumo ESTIMADO)
# --------------------------------------------------------------------------
CILINDRADA_L = 0.999       # D4D 1.0 16V
EFICIENCIA_VOL = 0.85      # eficiência volumétrica aproximada
AFR = 13.2                 # relação ar/combustível estequiométrica da gasolina C (E27)
DENSIDADE_COMB = 745.0     # g/L

PROTOCOLS = [
    ("0", "Automático (tenta todos)"),
    ("5", "5 - ISO 14230-4 KWP (init rápido)"),
    ("4", "4 - ISO 14230-4 KWP (5 baud)"),
    ("3", "3 - ISO 9141-2"),
    ("6", "6 - CAN 11bit 500k"),
    ("8", "8 - CAN 11bit 250k"),
    ("7", "7 - CAN 29bit 500k"),
    ("9", "9 - CAN 29bit 250k"),
    ("1", "1 - SAE J1850 PWM"),
    ("2", "2 - SAE J1850 VPW"),
]
# Carros Renault brasileiros dessa época normalmente usam linha K (KWP/ISO 9141)
FALLBACK_ORDER = ["5", "4", "3", "6", "8", "7", "9", "1", "2"]


def _fuel_status(d):
    return {1: "Malha aberta (frio)", 2: "Malha fechada (sonda)",
            4: "Malha aberta (carga/corte)", 8: "Malha aberta (falha)",
            16: "Malha fechada (c/ falha)"}.get(d[0], f"0x{d[0]:02X}")


# pid: (nome, unidade, casas decimais, decodificador)
PID_INFO = {
    0x0C: ("Rotação", "rpm", 0, lambda d: (d[0] * 256 + d[1]) / 4),
    0x0D: ("Velocidade", "km/h", 0, lambda d: d[0]),
    0x11: ("Borboleta (TPS)", "%", 1, lambda d: d[0] * 100 / 255),
    0x0B: ("Pressão coletor (MAP)", "kPa", 0, lambda d: d[0]),
    0x05: ("Temp. motor", "°C", 0, lambda d: d[0] - 40),
    0x0F: ("Temp. ar admissão", "°C", 0, lambda d: d[0] - 40),
    0x04: ("Carga do motor", "%", 1, lambda d: d[0] * 100 / 255),
    0x0E: ("Avanço de ignição", "°", 1, lambda d: d[0] / 2 - 64),
    0x06: ("Correção curta (STFT)", "%", 1, lambda d: (d[0] - 128) * 100 / 128),
    0x07: ("Correção longa (LTFT)", "%", 1, lambda d: (d[0] - 128) * 100 / 128),
    0x14: ("Sonda lambda B1S1", "V", 3, lambda d: d[0] / 200),
    0x03: ("Sistema de combustível", "", None, _fuel_status),
}
# Blocos Renault lidos (21A5+ devolvem lixo com o clone OBDBLE e deixam a leitura lenta)
RENAULT_LIDS = [0xA0, 0xA1, 0xA2, 0xA3, 0xA4]
# card do Painel -> (bloco, byte inicial, nº de bytes, conversão)
# Identificado na captura de 25/11/2026: 21A2 bytes 7-8 = rotação (lenta ~745, acelerando ~2000+)
RENAULT_MAP = {
    0x0C: (0xA2, 7, 2, lambda v: v),
    # PROVISÓRIO (captura 25/11 20:27): sobe devagar 175->185 com o motor esquentando.
    # Escala típica Renault x*0.625-40 -> 70..76 °C. Confirmar quando a ventoinha ligar (~95-100 °C).
    0x05: (0xA2, 13, 1, lambda v: v * 0.625 - 40),
    # Pedal do acelerador (PR126): x*100/256 %. Manual Renault: 14 % com o pé levantado (byte 36).
    0x11: (0xA3, 15, 1, lambda v: v * 100 / 256),
    # Correção da mistura (malha fechada): 0xA2 byte 16, 128 = neutro. Fica cravado em 128
    # na desaceleração/corte e com o motor desligado; ~+8..+13% em marcha lenta.
    # (Antes eu tinha interpretado como bateria /10 — estava ERRADO.)
    0x06: (0xA2, 16, 1, lambda v: (v - 128) * 100 / 128),
    # MAP: 0xA0 bytes 12-13 (16 bits), escala Renault 0,0578125 mbar/bit + 103.
    # Confere com o manual 17B (Vdiag 04): ~350 mbar em marcha lenta (medido 351-358) e
    # pressão atmosférica com o motor desligado (medido 910 mbar em Curitiba, ~906 esperado).
    # Leituras > 1100 mbar são lixo do adaptador clone e são descartadas.
    0x0B: (0xA0, 12, 2, lambda v: None if v * 0.0578125 + 103 > 1100 else (v * 0.0578125 + 103) / 10),
    # Sonda lambda montante: 0xA2 byte 11, 9,76 mV/bit. Em marcha lenta oscila 88-859 mV
    # (malha fechada). Ao acelerar ~820 mV (rica). Motor desligado ~2,5 V (sonda fria, sem sinal).
    0x14: (0xA2, 11, 1, lambda v: v * 0.00976),
    # Avanço: 0xA4 byte 15, x*0,375-23,625 °. Motor desligado -23,6° (manual -24°),
    # marcha lenta quente 0..2° (manual -1..+1°), ~30° a 2500 rpm.
    0x0E: (0xA4, 15, 1, lambda v: v * 0.375 - 23.625),
    # Bateria: 0xA2 byte 15, escala Renault 0,032 V/bit + 8 V. 13,7 V ligado, 12,6 V desligado
    # (caindo devagar após desligar). O ATRV do clone lê sempre ~2,2 V a mais.
    "volt": (0xA2, 15, 1, lambda v: v * 0.032 + 8),
    # PROVISÓRIO – temperatura do ar: 0xA2 byte 14, x*0,625-40 (~46 °C, estável).
    # Confirmar com partida a frio: motor frio => igual à temperatura da água (manual PR003).
    0x0F: (0xA2, 14, 1, lambda v: v * 0.625 - 40),
    # Tempo de injeção: 0xA4 bytes 13-14 (16 bits) x 4 µs. 0 ms desligado,
    # ~3,9-4,1 ms em marcha lenta (manual 3,5-4 ms).
    "inj": (0xA4, 13, 2, lambda v: v * 0.004),
}

FAST_PIDS = [0x0C, 0x0D, 0x11, 0x0B]          # lidos todo ciclo
SLOW_PIDS = [0x05, 0x0F, 0x04, 0x0E, 0x06, 0x07, 0x14, 0x03]  # rodízio
SLOW_PER_CYCLE = 2

DTC_DESC = {
    "P0100": "Circuito do sensor de fluxo de ar (MAF)",
    "P0105": "Circuito do sensor de pressão do coletor (MAP)",
    "P0106": "Sensor MAP – faixa/desempenho",
    "P0107": "Sensor MAP – sinal baixo",
    "P0108": "Sensor MAP – sinal alto",
    "P0110": "Circuito do sensor de temperatura do ar",
    "P0112": "Sensor temp. do ar – sinal baixo",
    "P0113": "Sensor temp. do ar – sinal alto",
    "P0115": "Circuito do sensor de temperatura do motor",
    "P0117": "Sensor temp. motor – sinal baixo",
    "P0118": "Sensor temp. motor – sinal alto",
    "P0120": "Circuito do sensor de posição da borboleta (TPS)",
    "P0122": "TPS – sinal baixo",
    "P0123": "TPS – sinal alto",
    "P0130": "Sonda lambda B1S1 – circuito",
    "P0131": "Sonda lambda B1S1 – tensão baixa",
    "P0132": "Sonda lambda B1S1 – tensão alta",
    "P0133": "Sonda lambda B1S1 – resposta lenta",
    "P0134": "Sonda lambda B1S1 – sem atividade",
    "P0135": "Sonda lambda B1S1 – aquecimento",
    "P0136": "Sonda lambda B1S2 – circuito",
    "P0141": "Sonda lambda B1S2 – aquecimento",
    "P0170": "Correção de combustível – banco 1",
    "P0171": "Mistura muito pobre – banco 1",
    "P0172": "Mistura muito rica – banco 1",
    "P0200": "Circuito dos injetores",
    "P0201": "Injetor cilindro 1",
    "P0202": "Injetor cilindro 2",
    "P0203": "Injetor cilindro 3",
    "P0204": "Injetor cilindro 4",
    "P0230": "Circuito do relé da bomba de combustível",
    "P0300": "Falha de ignição aleatória/múltipla",
    "P0301": "Falha de ignição – cilindro 1",
    "P0302": "Falha de ignição – cilindro 2",
    "P0303": "Falha de ignição – cilindro 3",
    "P0304": "Falha de ignição – cilindro 4",
    "P0325": "Sensor de detonação – circuito",
    "P0335": "Sensor de rotação/PMS – circuito",
    "P0340": "Sensor de fase (comando) – circuito",
    "P0351": "Bobina de ignição A – circuito",
    "P0352": "Bobina de ignição B – circuito",
    "P0420": "Eficiência do catalisador abaixo do limite",
    "P0443": "Válvula do cânister – circuito",
    "P0500": "Sensor de velocidade do veículo",
    "P0505": "Sistema de controle da marcha lenta",
    "P0506": "Marcha lenta abaixo do esperado",
    "P0507": "Marcha lenta acima do esperado",
    "P0560": "Tensão do sistema (bateria)",
    "P0562": "Tensão do sistema baixa",
    "P0563": "Tensão do sistema alta",
    "P0601": "Memória da ECU – erro de checksum",
    "P0605": "Memória da ECU – erro",
}


def decode_dtc(h4):
    first = int(h4[0], 16)
    return "PCBU"[first >> 2] + str(first & 3) + h4[1:]


def describe_dtc(code):
    if code in DTC_DESC:
        return DTC_DESC[code]
    if code[1] in "13":
        return "Código específico do fabricante (Renault)"
    return "Descrição não cadastrada – pesquise o código"


# --------------------------------------------------------------------------
# Transportes
# --------------------------------------------------------------------------
class SerialTransport:
    def __init__(self, port, baud):
        if serial is None:
            raise RuntimeError("pyserial não instalado. Rode: pip install pyserial")
        self.s = serial.Serial(port, baud, timeout=0.05)

    def write(self, data):
        self.s.write(data)

    def read(self):
        return self.s.read(self.s.in_waiting or 1)

    def flush_input(self):
        self.s.reset_input_buffer()

    def close(self):
        self.s.close()


class _AsyncLoop:
    """Loop asyncio em thread própria, usado pelo bleak."""
    loop = None

    @classmethod
    def run(cls, coro, timeout=20):
        if cls.loop is None:
            cls.loop = asyncio.new_event_loop()
            threading.Thread(target=cls.loop.run_forever, daemon=True).start()
        return asyncio.run_coroutine_threadsafe(coro, cls.loop).result(timeout)


def _uuid16(u):
    return f"0000{u.lower()}-0000-1000-8000-00805f9b34fb"


def ble_scan(seconds=6.0):
    """Retorna lista de (endereço, nome), adaptadores OBD primeiro."""
    if BleakScanner is None:
        raise RuntimeError("bleak não instalado. Rode: pip install bleak")
    devs = _AsyncLoop.run(BleakScanner.discover(timeout=seconds), seconds + 10)
    out = [(d.address, d.name or "(sem nome)") for d in devs]
    keys = ("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI")
    out.sort(key=lambda x: (not any(k in x[1].upper() for k in keys), x[1]))
    return out


class BleTransport:
    """ELM327 Bluetooth LE (ex.: 'OBDBLE', serviço FFF0, rx FFF1, tx FFF2)."""
    PREF_RX = [_uuid16("fff1"), _uuid16("ffe1"), _uuid16("18f1")]
    PREF_TX = [_uuid16("fff2"), _uuid16("ffe1"), _uuid16("2af1")]

    def __init__(self, address):
        if BleakClient is None:
            raise RuntimeError("bleak não instalado. Rode: pip install bleak")
        self.buf = bytearray()
        self.lock = threading.Lock()
        last = None
        for attempt in range(4):  # o Windows às vezes derruba a 1ª conexão GATT
            self.client = BleakClient(address)
            try:
                _AsyncLoop.run(self.client.connect(timeout=15), 25)
                self.rx, self.tx, self.tx_response = self._find_chars()
                _AsyncLoop.run(self.client.start_notify(self.rx, self._on_data), 10)
                return
            except Exception as e:
                last = e
                try:
                    _AsyncLoop.run(self.client.disconnect(), 8)
                except Exception:
                    pass
                time.sleep(3)
        raise ConnectionError(f"Bluetooth LE: {last} (o celular está conectado no adaptador?)")

    def _find_chars(self):
        chars = [c for s in self.client.services for c in s.characteristics]
        notif = [c for c in chars if {"notify", "indicate"} & set(c.properties)]
        wr = [c for c in chars if {"write", "write-without-response"} & set(c.properties)]

        def pick(lst, pref):
            for p in pref:
                for c in lst:
                    if c.uuid.lower() == p:
                        return c
            return lst[0] if lst else None

        rx, tx = pick(notif, self.PREF_RX), pick(wr, self.PREF_TX)
        if rx is None or tx is None:
            raise RuntimeError("Não encontrei as características seriais (FFF1/FFF2) no adaptador BLE.")
        resp = "write-without-response" not in tx.properties
        return rx, tx, resp

    def _on_data(self, _sender, data):
        with self.lock:
            self.buf.extend(data)

    def write(self, data):
        for i in range(0, len(data), 20):
            _AsyncLoop.run(self.client.write_gatt_char(self.tx, data[i:i + 20],
                                                       response=self.tx_response), 5)

    def read(self):
        with self.lock:
            if self.buf:
                data, self.buf = bytes(self.buf), bytearray()
                return data
        if not self.client.is_connected:
            raise ConnectionError("Conexão Bluetooth LE perdida")
        time.sleep(0.01)
        return b""

    def flush_input(self):
        with self.lock:
            self.buf.clear()

    def close(self):
        try:
            _AsyncLoop.run(self.client.disconnect(), 8)
        except Exception:
            pass


class TcpTransport:
    def __init__(self, host, port):
        self.s = socket.create_connection((host, port), timeout=5)
        self.s.settimeout(0.05)

    def write(self, data):
        self.s.sendall(data)

    def read(self):
        try:
            data = self.s.recv(512)
            if data == b"":
                raise ConnectionError("Conexão Wi-Fi encerrada pelo adaptador")
            return data
        except socket.timeout:
            return b""

    def flush_input(self):
        self.s.setblocking(False)
        try:
            while self.s.recv(512):
                pass
        except (BlockingIOError, OSError):
            pass
        finally:
            self.s.settimeout(0.05)

    def close(self):
        self.s.close()


class SimTransport:
    """Simula um ELM327 ligado a um carro em funcionamento (linha K)."""
    SUPPORTED = {0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D,
                 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x1C}

    def __init__(self, renault=False):
        self.out = b""
        self.t0 = time.time()
        self.dtcs = ["0133", "0420"]
        self.renault = renault  # imita o Clio real: recusa o modo 01, responde 21xx

    def _resp(self, cmd):
        c = cmd.upper().replace(" ", "")
        if self.renault:
            if c == "0100":
                return "410000000000"
            if c.startswith("01"):
                return "7F0112"
            if c == "2180":
                return "6180820053915204303031820040024600D450000203E2FF0000"
            if c in ("21A0", "21A1", "21A2"):
                t = time.time() - self.t0
                rpm = int(850 + 1800 * max(0, math.sin(t / 4)))
                temp = int(min(90, 20 + t / 2)) + 40
                body = [0] * 24
                body[7], body[8], body[2] = rpm >> 8, rpm & 255, temp
                body[3] = random.randint(0, 255)
                return "61" + c[2:] + "".join(f"{b:02X}" for b in body)
            if c.startswith("21"):
                return "7F2112"
        if c == "ATZ":
            return "ELM327 v1.5 (SIMULADOR)"
        if c == "ATRV":
            return f"{13.8 + random.uniform(-0.2, 0.2):.1f}V"
        if c == "ATDPN":
            return "A5"
        if c == "ATDP":
            return "AUTO, ISO 14230-4 (KWP FAST)"
        if c == "ATI":
            return "ELM327 v1.5"
        if c.startswith("AT"):
            return "OK"
        if c == "0100":
            bits = sum(1 << (32 - p) for p in self.SUPPORTED)
            return "4100" + f"{bits:08X}"
        if c in ("0120", "0140"):
            return "NO DATA"
        if c == "03":
            codes = (self.dtcs + ["0000"] * 3)[:3]
            return "43" + "".join(codes)
        if c == "07":
            return "47000000000000"
        if c == "04":
            self.dtcs = []
            return "44"
        if c.startswith("01") and len(c) == 4:
            pid = int(c[2:], 16)
            if pid not in self.SUPPORTED:
                return "NO DATA"
            t = time.time() - self.t0
            rpm = 850 + 1800 * max(0, math.sin(t / 4))
            spd = max(0, 60 * math.sin(t / 8))
            tps = max(0, 40 * math.sin(t / 4))
            vals = {
                0x01: [0x80 | len(self.dtcs) if self.dtcs else 0, 0x07, 0x65, 0x00],
                0x03: [2, 0],
                0x04: [int(40 + tps * 1.5)],
                0x05: [int(40 + min(90, 20 + t / 2))],
                0x06: [128 + random.randint(-6, 6)],
                0x07: [131],
                0x0B: [int(32 + tps * 1.6)],
                0x0C: list(int(rpm * 4).to_bytes(2, "big")),
                0x0D: [int(spd)],
                0x0E: [int((12 + tps / 4 + 64) * 2)],
                0x0F: [int(40 + 32)],
                0x11: [int(tps * 255 / 100 + 20)],
                0x13: [0x03],
                0x14: [random.randint(20, 170), 0xFF],
                0x1C: [0x06],
            }[pid]
            return "41" + c[2:] + "".join(f"{v:02X}" for v in vals)
        return "?"

    def write(self, data):
        cmd = data.decode().strip()
        time.sleep(0.03)
        self.out += (self._resp(cmd) + "\r\r>").encode()

    def read(self):
        if not self.out:
            time.sleep(0.01)
            return b""
        data, self.out = self.out, b""
        return data

    def flush_input(self):
        self.out = b""

    def close(self):
        pass


# --------------------------------------------------------------------------
# Driver ELM327
# --------------------------------------------------------------------------
class ELM327:
    def __init__(self, transport, logger=None):
        self.t = transport
        self.logger = logger
        self.version = ""
        self.protocol_num = ""
        self.protocol_name = ""
        self.is_can = False

    def send(self, cmd, timeout=3.0, quiet=False):
        self.t.flush_input()
        self.t.write((cmd + "\r").encode("ascii"))
        buf = bytearray()
        deadline = time.time() + timeout
        while time.time() < deadline:
            chunk = self.t.read()
            if chunk:
                buf.extend(chunk)
                if b">" in buf:
                    break
        text = buf.decode("ascii", errors="ignore").replace(">", "").replace("\x00", "")
        lines = [l.strip() for l in re.split(r"[\r\n]+", text) if l.strip()]
        if lines and lines[0].replace(" ", "").upper() == cmd.replace(" ", "").upper():
            lines = lines[1:]  # remove eco
        if not quiet and self.logger:
            self.logger(f">> {cmd}\n" + ("\n".join(lines) if lines else "(sem resposta / timeout)"))
        return lines

    @staticmethod
    def _hex_lines(lines):
        out = []
        for l in lines:
            l = l.replace(" ", "").upper()
            l = re.sub(r"^[0-9A-F]:", "", l)  # prefixo de frame CAN multi-linha
            if re.fullmatch(r"[0-9A-F]+", l):
                out.append(l)
        return out

    def initialize(self, protocol="0", status=None):
        status = status or (lambda s: None)
        status("Reiniciando ELM327...")
        lines = self.send("ATZ", timeout=5)
        time.sleep(0.3)
        self.version = next((l for l in lines if "ELM" in l.upper()), " ".join(lines))
        if not self.version:
            lines = self.send("ATI")
            self.version = " ".join(lines)
        for c in ("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1"):
            self.send(c)
        # automático é tentado 2x (o clone OBDBLE só conecta no Clio pelo automático)
        order = [protocol] if protocol != "0" else ["0", "0"] + FALLBACK_ORDER
        ok = False
        for i, p in enumerate(order, 1):
            name = dict(PROTOCOLS).get(p, p)
            status(f"Tentativa {i}/{len(order)} – protocolo: {name} ...")
            self.send("ATPC")
            self.send(f"ATSP{p}")
            if self.query_pid(0x00, timeout=20 if p == "0" else 8) is not None:
                ok = True
                break
        if not ok:
            return False
        dpn = "".join(self.send("ATDPN")).upper().lstrip("A")
        self.protocol_num = dpn[:1]
        self.is_can = self.protocol_num != "" and self.protocol_num in "6789ABC"
        self.protocol_name = " ".join(self.send("ATDP"))
        return True

    def query_pid(self, pid, timeout=3.0, quiet=False):
        lines = self.send(f"01{pid:02X}", timeout=timeout, quiet=quiet)
        target = f"41{pid:02X}"
        for l in self._hex_lines(lines):
            i = l.find(target)
            if i >= 0:
                data = l[i + 4:]
                data = data[: len(data) // 2 * 2]
                if data:
                    return bytes.fromhex(data)
        return None

    def supported_pids(self):
        sup = set()
        for base in (0x00, 0x20, 0x40):
            if base and base not in sup:
                break
            d = self.query_pid(base)
            if not d or len(d) < 4:
                break
            bits = int.from_bytes(d[:4], "big")
            for i in range(32):
                if bits & (1 << (31 - i)):
                    sup.add(base + i + 1)
        return sup

    def voltage(self):
        txt = "".join(self.send("ATRV", quiet=True))
        m = re.search(r"(\d+(\.\d+)?)", txt)
        return float(m.group(1)) if m else None

    def mil_status(self):
        d = self.query_pid(0x01)
        if not d:
            return None
        return bool(d[0] & 0x80), d[0] & 0x7F

    def read_dtcs(self, mode="03"):
        resp = {"03": "43", "07": "47"}[mode]
        hexs = self._hex_lines(self.send(mode, timeout=10))
        codes = []
        if self.is_can:
            hexs = [h for h in hexs if len(h) != 3]  # linha de tamanho do multi-frame
            data = "".join(hexs)
            i = data.find(resp)
            if i >= 0:
                payload = data[i + 2:]
                count = int(payload[:2], 16) if len(payload) >= 2 else 0
                body = payload[2:2 + count * 4]
                codes = [body[j:j + 4] for j in range(0, len(body) - 3, 4)]
        else:
            for h in hexs:
                if not h.startswith(resp):
                    continue
                body = h[2:]
                codes += [body[j:j + 4] for j in range(0, len(body) - 3, 4)]
        result = []
        for c in codes:
            if c != "0000":
                code = decode_dtc(c)
                if code not in result:
                    result.append(code)
        return result

    def clear_dtcs(self):
        return any("44" in h for h in self._hex_lines(self.send("04", timeout=10)))

    # ----- Serviço Renault KWP2000: 21 = ReadDataByLocalIdentifier -----
    def renault_read(self, lid, quiet=True):
        """Retorna os bytes de dados de '21xx' (sem o '61xx'), ou None."""
        target = f"61{lid:02X}"
        for l in self._hex_lines(self.send(f"21{lid:02X}", timeout=3, quiet=quiet)):
            i = l.find(target)
            if i >= 0:
                data = l[i + 4:]
                data = data[: len(data) // 2 * 2]
                return bytes.fromhex(data)
        return None

    def renault_ident(self):
        d = self.renault_read(0x80, quiet=False)
        if not d or len(d) < 5:
            return None
        ref = d[:5].hex().upper()
        return ref


# --------------------------------------------------------------------------
# Interface gráfica
# --------------------------------------------------------------------------
class App:
    def __init__(self, root):
        self.root = root
        root.title(APP_TITLE)
        root.geometry("1000x680")
        root.minsize(820, 560)

        self.elm = None
        self.transport = None
        self.monitoring = False
        self.supported = set()
        self.fail_count = {}
        self.slow_idx = 0
        self.cycle = 0
        self.latest = {}
        self.csv_file = None
        self.csv_writer = None
        self.csv_header_done = False
        self.pending_note = ""
        # modo Renault (ECU que recusa o modo 01 e responde 21xx)
        self.renault_mode = False
        self.renault_lids = []
        self.renault_ref = None
        self.renault_data = {}
        self.renault_stats = {}
        self.capture_phase = None
        self.capture_rows = []
        self.reopen_fn = None
        self.proto_code = "0"
        self.renault_misses = 0

        self.jobs = queue.Queue()
        self.uiq = queue.Queue()
        self.stop_event = threading.Event()
        threading.Thread(target=self._worker_loop, daemon=True).start()

        self._build_ui()
        self.root.after(80, self._process_ui)
        root.protocol("WM_DELETE_WINDOW", self._on_close)

    # ---------- infraestrutura de threads ----------
    def call_ui(self, fn, *args):
        self.uiq.put((fn, args))

    def _process_ui(self):
        try:
            while True:
                fn, args = self.uiq.get_nowait()
                fn(*args)
        except queue.Empty:
            pass
        self.root.after(80, self._process_ui)

    def run_job(self, fn):
        self.jobs.put(fn)

    def _worker_loop(self):
        while not self.stop_event.is_set():
            try:
                job = self.jobs.get(timeout=0.05)
            except queue.Empty:
                if self.monitoring and self.elm:
                    try:
                        self._poll_cycle()
                    except Exception as e:
                        if self._try_reconnect(e):
                            continue
                        self.monitoring = False
                        self.call_ui(self._on_monitor_stopped)
                        self.call_ui(self._error, f"Erro na leitura: {e}")
                continue
            try:
                job()
            except Exception as e:
                self.call_ui(self._error, str(e))

    def _try_reconnect(self, err, attempts=4):
        """Reabre o adaptador e reinicia o ELM327 sem parar a leitura/captura."""
        if not self.reopen_fn:
            return False
        for n in range(1, attempts + 1):
            if not self.monitoring or self.stop_event.is_set():
                return False
            time.sleep(6 if n == 1 else 2)  # a sessão KWP anterior da ECU precisa expirar
            self.call_ui(self.set_status, f"Conexão caiu ({err}) – reconectando {n}/{attempts}...")
            self.call_ui(self.term_write, f"[RECONEXÃO {n}] motivo: {err}")
            try:
                self.transport.close()
            except Exception:
                pass
            try:
                t = self.reopen_fn()
                elm = ELM327(t, logger=self.log_from_worker)
                if elm.initialize(self.proto_code):
                    self.transport, self.elm = t, elm
                    self.renault_misses = 0
                    self.call_ui(self.set_status, "Reconectado – lendo dados...")
                    return True
                t.close()
                err = "ECU não respondeu"
            except Exception as e:
                err = e
            time.sleep(2)
        return False

    def _error(self, msg):
        self.set_status(msg)
        self.term_write(f"[ERRO] {msg}")
        messagebox.showerror(APP_TITLE, msg)

    # ---------- construção da UI ----------
    def _build_ui(self):
        style = ttk.Style()
        try:
            style.theme_use("vista")
        except tk.TclError:
            pass
        style.configure("CardTitle.TLabel", font=("Segoe UI", 10), foreground="#555")
        style.configure("CardValue.TLabel", font=("Segoe UI", 24, "bold"))
        style.configure("CardUnit.TLabel", font=("Segoe UI", 10), foreground="#777")

        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=8, pady=(8, 0))
        self.nb = nb

        self._build_conn_tab(nb)
        self._build_dash_tab(nb)
        self._build_renault_tab(nb)
        self._build_diag_tab(nb)
        self._build_term_tab(nb)

        self.status_var = tk.StringVar(value="Desconectado")
        ttk.Label(self.root, textvariable=self.status_var, anchor="w",
                  relief="sunken", padding=4).pack(fill="x", side="bottom", padx=8, pady=6)

    def _build_conn_tab(self, nb):
        f = ttk.Frame(nb, padding=14)
        nb.add(f, text="  Conexão  ")

        self.conn_type = tk.StringVar(value="ble")
        box = ttk.LabelFrame(f, text="Tipo de adaptador", padding=10)
        box.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        for txt, val in (("Bluetooth LE (ex.: OBDBLE)", "ble"),
                         ("USB / Bluetooth clássico (porta COM)", "serial"),
                         ("Wi-Fi", "wifi"), ("Simulador (sem carro)", "sim"),
                         ("Simulador Renault (imita o Clio)", "sim_renault")):
            ttk.Radiobutton(box, text=txt, value=val, variable=self.conn_type).pack(anchor="w", pady=2)

        cfg = ttk.LabelFrame(f, text="Configuração", padding=10)
        cfg.grid(row=0, column=1, sticky="nsew")
        ttk.Label(cfg, text="Porta COM:").grid(row=0, column=0, sticky="w", pady=3)
        self.port_var = tk.StringVar()
        self.port_cb = ttk.Combobox(cfg, textvariable=self.port_var, width=38)
        self.port_cb.grid(row=0, column=1, sticky="w")
        ttk.Button(cfg, text="Atualizar", command=self.refresh_ports).grid(row=0, column=2, padx=5)

        ttk.Label(cfg, text="Velocidade (baud):").grid(row=1, column=0, sticky="w", pady=3)
        self.baud_var = tk.StringVar(value="38400")
        ttk.Combobox(cfg, textvariable=self.baud_var, width=12,
                     values=["38400", "9600", "57600", "115200", "230400"]).grid(row=1, column=1, sticky="w")

        ttk.Label(cfg, text="IP Wi-Fi:").grid(row=2, column=0, sticky="w", pady=3)
        self.host_var = tk.StringVar(value="192.168.0.10")
        ttk.Entry(cfg, textvariable=self.host_var, width=18).grid(row=2, column=1, sticky="w")
        ttk.Label(cfg, text="Porta TCP:").grid(row=3, column=0, sticky="w", pady=3)
        self.tcp_port_var = tk.StringVar(value="35000")
        ttk.Entry(cfg, textvariable=self.tcp_port_var, width=8).grid(row=3, column=1, sticky="w")

        ttk.Label(cfg, text="Protocolo OBD:").grid(row=4, column=0, sticky="w", pady=3)
        self.proto_var = tk.StringVar(value=PROTOCOLS[0][1])
        ttk.Combobox(cfg, textvariable=self.proto_var, width=38, state="readonly",
                     values=[p[1] for p in PROTOCOLS]).grid(row=4, column=1, sticky="w")

        ttk.Label(cfg, text="Dispositivo BLE:").grid(row=5, column=0, sticky="w", pady=3)
        self.ble_var = tk.StringVar()
        self.ble_cb = ttk.Combobox(cfg, textvariable=self.ble_var, width=38)
        self.ble_cb.grid(row=5, column=1, sticky="w")
        self.btn_scan = ttk.Button(cfg, text="Procurar", command=self.scan_ble)
        self.btn_scan.grid(row=5, column=2, padx=5)

        btns = ttk.Frame(f)
        btns.grid(row=1, column=0, columnspan=2, sticky="w", pady=12)
        self.btn_connect = ttk.Button(btns, text="Conectar", command=self.connect)
        self.btn_connect.pack(side="left")
        self.btn_disconnect = ttk.Button(btns, text="Desconectar", command=self.disconnect, state="disabled")
        self.btn_disconnect.pack(side="left", padx=6)

        info = ttk.LabelFrame(f, text="Informações", padding=10)
        info.grid(row=2, column=0, columnspan=2, sticky="nsew")
        self.info_text = tk.Text(info, height=12, font=("Consolas", 10), wrap="word")
        self.info_text.pack(fill="both", expand=True)
        self.info_text.insert("end",
            "Dicas:\n"
            "• Ligue a ignição (ou o motor) antes de conectar.\n"
            "• Bluetooth LE (OBDBLE): NÃO precisa parear no Windows. Clique em 'Procurar',\n"
            "  escolha o OBDBLE e conecte. Feche o app do celular antes: o adaptador BLE\n"
            "  aceita só uma conexão por vez.\n"
            "• Bluetooth clássico: pareie o ELM327 no Windows (PIN 1234 ou 0000) e escolha a porta COM\n"
            "  'de saída' criada. USB: instale o driver CH340/FTDI se a porta não aparecer.\n"
            "• Wi-Fi: conecte o PC à rede do adaptador (geralmente 192.168.0.10:35000).\n"
            "• Clio 2005: funciona em ISO 14230-4 KWP FAST (Automático ou protocolo 5).\n"
            "  Ao reconectar, espere ~6 s: a ECU recusa nova conexão enquanto a anterior expira.\n")
        f.columnconfigure(1, weight=1)
        f.rowconfigure(2, weight=1)
        self.refresh_ports()

    def _build_dash_tab(self, nb):
        f = ttk.Frame(nb, padding=10)
        nb.add(f, text="  Painel  ")
        top = ttk.Frame(f)
        top.pack(fill="x")
        self.btn_mon = ttk.Button(top, text="▶ Iniciar leitura", command=self.toggle_monitor, state="disabled")
        self.btn_mon.pack(side="left")
        self.log_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(top, text="Gravar em CSV", variable=self.log_var,
                        command=self.toggle_csv).pack(side="left", padx=12)
        self.rate_var = tk.StringVar(value="")
        ttk.Label(top, textvariable=self.rate_var).pack(side="right")

        grid = ttk.Frame(f)
        grid.pack(fill="both", expand=True, pady=10)
        self.cards = {}
        keys = FAST_PIDS + SLOW_PIDS + ["volt", "inj", "cons_lh", "cons_kml"]
        extra = {"volt": ("Bateria", "V"), "inj": ("Tempo de injeção", "ms"),
                 "cons_lh": ("Consumo estimado", "L/h"),
                 "cons_kml": ("Consumo estimado", "km/L")}
        cols = 4
        for idx, k in enumerate(keys):
            name, unit = (PID_INFO[k][0], PID_INFO[k][1]) if k in PID_INFO else extra[k]
            card = ttk.LabelFrame(grid, padding=8)
            card.grid(row=idx // cols, column=idx % cols, sticky="nsew", padx=5, pady=5)
            ttk.Label(card, text=name, style="CardTitle.TLabel").pack(anchor="w")
            val = tk.StringVar(value="--")
            lbl = ttk.Label(card, textvariable=val, style="CardValue.TLabel")
            if k == 0x03:
                lbl.configure(font=("Segoe UI", 12, "bold"))
            lbl.pack(anchor="w")
            ttk.Label(card, text=unit, style="CardUnit.TLabel").pack(anchor="w")
            self.cards[k] = val
        for c in range(cols):
            grid.columnconfigure(c, weight=1)
        ttk.Label(f, foreground="#777",
                  text="Consumo calculado por MAP/IAT/RPM (estimativa, sem sensor MAF). "
                       "PIDs não suportados pela ECU ficam com '--'.").pack(anchor="w")

    def _build_renault_tab(self, nb):
        f = ttk.Frame(nb, padding=10)
        nb.add(f, text="  Renault (bruto)  ")
        self.renault_tab = f
        top = ttk.Frame(f)
        top.pack(fill="x")
        self.btn_mon2 = ttk.Button(top, text="▶ Iniciar leitura", command=self.toggle_monitor,
                                   state="disabled")
        self.btn_mon2.pack(side="left")
        ttk.Checkbutton(top, text="Gravar em CSV", variable=self.log_var,
                        command=self.toggle_csv).pack(side="left", padx=12)
        ttk.Button(top, text="Zerar mín/máx", command=self._reset_renault_stats).pack(side="left")
        self.btn_guided = ttk.Button(top, text="Captura guiada (avisa quando acelerar)",
                                     command=self.start_guided, state="disabled")
        self.btn_guided.pack(side="left", padx=12)
        ttk.Label(top, textvariable=self.rate_var).pack(side="right")

        note = ttk.Frame(f)
        note.pack(fill="x", pady=(8, 4))
        ttk.Label(note, text="Anotação no CSV:").pack(side="left")
        self.note_var = tk.StringVar()
        e = ttk.Entry(note, textvariable=self.note_var, width=40)
        e.pack(side="left", padx=6)
        e.bind("<Return>", lambda ev: self._mark_note())
        ttk.Button(note, text="Marcar", command=self._mark_note).pack(side="left")
        for txt in ("marcha lenta", "acelerando", "soltou", "ventoinha ligou"):
            ttk.Button(note, text=txt, command=lambda t=txt: self._mark_note(t)).pack(side="left", padx=2)

        cols = ("bloco", "byte", "hex", "dec", "min", "max", "palavra")
        self.rtree = ttk.Treeview(f, columns=cols, show="headings")
        heads = {"bloco": "Bloco", "byte": "Byte", "hex": "Hex", "dec": "Decimal",
                 "min": "Mín", "max": "Máx", "palavra": "16 bits (este+próx.)"}
        for c in cols:
            self.rtree.heading(c, text=heads[c])
            self.rtree.column(c, width=150 if c == "palavra" else 80, anchor="center")
        self.rtree.tag_configure("changing", background="#fff3b0", foreground="#000")
        sb = ttk.Scrollbar(f, orient="vertical", command=self.rtree.yview)
        self.rtree.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.rtree.pack(fill="both", expand=True)
        ttk.Label(f, foreground="#777",
                  text="Linhas amarelas = bytes que variaram. Os significados ainda estão sendo "
                       "identificados (comparando marcha lenta × aceleração × aquecimento).").pack(anchor="w")

    def _rebuild_renault_tree(self):
        self.rtree.delete(*self.rtree.get_children())
        for lid in self.renault_lids:
            for i in range(len(self.renault_data.get(lid, b""))):
                self.rtree.insert("", "end", iid=f"{lid:02X}_{i}",
                                  values=(f"21{lid:02X}", i, "", "", "", "", ""))
        self._update_renault_tree()

    def _update_renault_tree(self):
        for lid in self.renault_lids:
            d = self.renault_data.get(lid, b"")
            for i, b in enumerate(d):
                iid = f"{lid:02X}_{i}"
                if not self.rtree.exists(iid):
                    self.rtree.insert("", "end", iid=iid, values=(f"21{lid:02X}", i, "", "", "", "", ""))
                mn, mx = self.renault_stats.get((lid, i), (b, b))
                word = (b << 8 | d[i + 1]) if i + 1 < len(d) else ""
                self.rtree.item(iid, values=(f"21{lid:02X}", i, f"0x{b:02X}", b, mn, mx, word),
                                tags=("changing",) if mn != mx else ())

    def _reset_renault_stats(self):
        self.renault_stats = {}
        if self.renault_lids:
            self._update_renault_tree()

    # ---------- captura guiada ----------
    GUIDE_STEPS = (
        [("PREPARE-SE", "Motor ligado em marcha lenta.\nCarro PARADO, em ponto morto e freio de mão puxado.",
          8, None, "#455a64")]
        + [("MARCHA LENTA", "NÃO toque no acelerador.", 20, "marcha lenta", "#2e7d32")]
        + [("ACELERE!", "Segure em ~2.500 rpm\n(mais ou menos metade do conta-giros).", 6, "acelerando", "#c62828"),
           ("SOLTE", "Tire o pé do acelerador e espere.", 12, "soltou", "#f9a825")] * 3
    )

    def start_guided(self):
        if not self.renault_mode:
            messagebox.showinfo(APP_TITLE, "A captura guiada é para o modo Renault.\n"
                                "Conecte ao carro (a ECU precisa estar em modo Renault).")
            return
        if not self.monitoring:
            self.toggle_monitor()
        self.capture_rows = []
        w = tk.Toplevel(self.root)
        w.title("Captura guiada")
        w.attributes("-topmost", True)
        w.state("zoomed")
        w.protocol("WM_DELETE_WINDOW", lambda: self._end_guided(w, cancelled=True))
        self.g_title = tk.Label(w, font=("Segoe UI", 72, "bold"), fg="white")
        self.g_title.pack(expand=True, fill="both")
        self.g_text = tk.Label(w, font=("Segoe UI", 26), fg="white", justify="center")
        self.g_text.pack(fill="x")
        self.g_count = tk.Label(w, font=("Segoe UI", 120, "bold"), fg="white")
        self.g_count.pack(expand=True, fill="both")
        self.g_step = tk.Label(w, font=("Segoe UI", 14), fg="white")
        self.g_step.pack(fill="x", pady=10)
        tk.Button(w, text="Cancelar", font=("Segoe UI", 14),
                  command=lambda: self._end_guided(w, cancelled=True)).pack(pady=10)
        self.g_win = w
        self._guided_step(w, 0)

    def _beep(self, kind):
        try:
            import winsound
            if kind == "acelerando":
                winsound.Beep(1500, 250)
                winsound.Beep(1500, 250)
            else:
                winsound.Beep(800, 300)
        except Exception:
            pass

    def _guided_step(self, w, idx):
        if not w.winfo_exists():
            return
        if idx >= len(self.GUIDE_STEPS):
            return self._end_guided(w)
        title, text, secs, phase, color = self.GUIDE_STEPS[idx]
        self.capture_phase = phase
        for lbl in (w, self.g_title, self.g_text, self.g_count, self.g_step):
            lbl.configure(bg=color)
        self.g_title.configure(text=title)
        self.g_text.configure(text=text)
        self.g_step.configure(text=f"Etapa {idx + 1} de {len(self.GUIDE_STEPS)}")
        threading.Thread(target=self._beep, args=(phase,), daemon=True).start()
        self._guided_tick(w, idx, secs)

    def _guided_tick(self, w, idx, remaining):
        if not w.winfo_exists():
            return
        if remaining <= 0:
            return self._guided_step(w, idx + 1)
        self.g_count.configure(text=str(remaining))
        w.after(1000, lambda: self._guided_tick(w, idx, remaining - 1))

    def _end_guided(self, w, cancelled=False):
        import os
        self.capture_phase = None
        rows, self.capture_rows = self.capture_rows, []
        if w.winfo_exists():
            w.destroy()
        if cancelled or not rows:
            self.set_status("Captura guiada cancelada." if cancelled else "Captura sem dados (conexão caiu?)")
            return
        folder = os.path.join(os.path.dirname(os.path.abspath(__file__)), "capturas")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, f"captura_{datetime.now():%Y%m%d_%H%M%S}.csv")
        lids = sorted({l for _, _, d in rows for l in d})
        with open(path, "w", newline="", encoding="utf-8-sig") as fh:
            wr = csv.writer(fh, delimiter=";")
            header = ["hora", "fase"]
            for l in lids:
                n = max(len(d.get(l, b"")) for _, _, d in rows)
                header += [f"{l:02X}_{i:02d}" for i in range(n)]
            wr.writerow(header)
            for ts, ph, d in rows:
                row = [datetime.fromtimestamp(ts).isoformat(timespec="milliseconds"), ph]
                for l in lids:
                    row += list(d.get(l, b""))
                wr.writerow(row)
        cands = self._rpm_candidates(rows)
        msg = f"Captura salva ({len(rows)} amostras):\n{path}\n\n"
        if cands:
            msg += "Bytes que mais mudaram ao acelerar (prováveis rotação/borboleta/MAP):\n"
            msg += "\n".join(f"• {c}" for c in cands)
        msg += "\n\nAvise o Claude que a captura terminou para os valores irem ao Painel."
        self.set_status(f"Captura salva em {path}")
        messagebox.showinfo(APP_TITLE, msg)

    @staticmethod
    def _rpm_candidates(rows, top=6):
        """Compara marcha lenta x acelerando para cada byte e cada palavra de 16 bits."""
        def stats(xs):
            if not xs:
                return None, None
            m = sum(xs) / len(xs)
            return m, (sum((x - m) ** 2 for x in xs) / len(xs)) ** 0.5
        series = {}
        for _, ph, d in rows:
            if ph not in ("marcha lenta", "acelerando"):
                continue
            for l, b in d.items():
                for i in range(len(b)):
                    series.setdefault((l, i, 8), {}).setdefault(ph, []).append(b[i])
                    if i + 1 < len(b):
                        series.setdefault((l, i, 16), {}).setdefault(ph, []).append(b[i] << 8 | b[i + 1])
        scored = []
        for (l, i, bits), ph in series.items():
            mi, si = stats(ph.get("marcha lenta", []))
            ma, sa = stats(ph.get("acelerando", []))
            if mi is None or ma is None or ma == mi:
                continue
            score = abs(ma - mi) / ((si or 0) + (sa or 0) + 1)
            name = f"21{l:02X} byte {i}" + (f"+{i + 1} (16 bits)" if bits == 16 else "")
            scored.append((score, f"{name}: lenta≈{mi:.0f}  acelerando≈{ma:.0f}"))
        scored.sort(reverse=True)
        return [s for _, s in scored[:top]]

    def _mark_note(self, text=None):
        text = text or self.note_var.get().strip()
        if not text:
            return
        self.pending_note = text
        self.note_var.set("")
        self.set_status(f"Anotação marcada: {text}" + ("" if self.csv_writer else " (CSV desligado!)"))

    def _build_diag_tab(self, nb):
        f = ttk.Frame(nb, padding=10)
        nb.add(f, text="  Diagnóstico (DTC)  ")
        top = ttk.Frame(f)
        top.pack(fill="x")
        self.diag_btns = [
            ttk.Button(top, text="Ler códigos de falha", command=lambda: self.read_dtcs("03")),
            ttk.Button(top, text="Ler códigos pendentes", command=lambda: self.read_dtcs("07")),
            ttk.Button(top, text="Apagar códigos / luz de injeção", command=self.clear_dtcs),
        ]
        for b in self.diag_btns:
            b.pack(side="left", padx=(0, 6))
            b.configure(state="disabled")
        self.mil_var = tk.StringVar(value="Luz de injeção (MIL): --")
        ttk.Label(f, textvariable=self.mil_var, font=("Segoe UI", 12, "bold")).pack(anchor="w", pady=10)
        self.tree = ttk.Treeview(f, columns=("code", "type", "desc"), show="headings")
        self.tree.heading("code", text="Código")
        self.tree.heading("type", text="Tipo")
        self.tree.heading("desc", text="Descrição")
        self.tree.column("code", width=90, anchor="center")
        self.tree.column("type", width=110, anchor="center")
        self.tree.column("desc", width=600)
        self.tree.pack(fill="both", expand=True)

    def _build_term_tab(self, nb):
        f = ttk.Frame(nb, padding=10)
        nb.add(f, text="  Terminal  ")
        self.term = tk.Text(f, font=("Consolas", 10), bg="#111", fg="#0f0",
                            insertbackground="#0f0", wrap="word")
        self.term.pack(fill="both", expand=True)
        bot = ttk.Frame(f)
        bot.pack(fill="x", pady=(6, 0))
        self.cmd_var = tk.StringVar()
        e = ttk.Entry(bot, textvariable=self.cmd_var, font=("Consolas", 11))
        e.pack(side="left", fill="x", expand=True)
        e.bind("<Return>", lambda ev: self.send_raw())
        ttk.Button(bot, text="Enviar", command=self.send_raw).pack(side="left", padx=6)
        ttk.Button(bot, text="Limpar", command=lambda: self.term.delete("1.0", "end")).pack(side="left")
        ttk.Label(f, foreground="#777",
                  text="Exemplos: ATI  ATRV  ATDP  0100  010C  0105  03").pack(anchor="w")

    # ---------- helpers UI ----------
    def set_status(self, msg):
        self.status_var.set(msg)

    def term_write(self, msg):
        self.term.insert("end", msg + "\n")
        self.term.see("end")

    def log_from_worker(self, msg):
        self.call_ui(self.term_write, msg)

    def refresh_ports(self):
        if serial is None:
            self.port_cb["values"] = ["(instale pyserial)"]
            return
        ports = [f"{p.device} – {p.description}" for p in serial.tools.list_ports.comports()]
        self.port_cb["values"] = ports
        if ports and not self.port_var.get():
            self.port_var.set(ports[0])

    def scan_ble(self):
        self.btn_scan.configure(state="disabled")
        self.set_status("Procurando dispositivos Bluetooth LE (6 s)...")

        def job():
            try:
                devs = ble_scan()
            finally:
                self.call_ui(self.btn_scan.configure, {"state": "normal"})
            self.call_ui(self._on_ble_scan, devs)
        self.run_job(job)

    def _on_ble_scan(self, devs):
        vals = [f"{a} – {n}" for a, n in devs]
        self.ble_cb["values"] = vals
        if vals:
            self.ble_var.set(vals[0])
        self.set_status(f"{len(devs)} dispositivo(s) BLE encontrado(s)" if devs else
                        "Nenhum dispositivo BLE encontrado – adaptador ligado? Bluetooth do PC ativo?")

    def _set_connected_ui(self, connected):
        self.btn_connect.configure(state="disabled" if connected else "normal")
        self.btn_disconnect.configure(state="normal" if connected else "disabled")
        self.btn_mon.configure(state="normal" if connected else "disabled")
        self.btn_mon2.configure(state="normal" if connected else "disabled")
        self.btn_guided.configure(state="normal" if connected else "disabled")
        for b in self.diag_btns:
            b.configure(state="normal" if connected else "disabled")

    # ---------- conexão ----------
    def connect(self):
        kind = self.conn_type.get()
        proto = next(code for code, name in PROTOCOLS if name == self.proto_var.get())
        port = self.port_var.get().split(" ")[0]
        baud = int(self.baud_var.get() or 38400)
        host, tport = self.host_var.get().strip(), int(self.tcp_port_var.get() or 35000)
        ble_addr = self.ble_var.get().split(" ")[0]
        if kind == "serial" and not port:
            messagebox.showwarning(APP_TITLE, "Selecione a porta COM do adaptador.")
            return
        self.btn_connect.configure(state="disabled")
        self.set_status("Conectando...")

        def open_transport():
            if kind == "ble":
                return BleTransport(ble_addr)
            if kind == "serial":
                return SerialTransport(port, baud)
            if kind == "wifi":
                return TcpTransport(host, tport)
            return SimTransport(renault=(kind == "sim_renault"))

        def job():
            nonlocal ble_addr
            try:
                if kind == "ble":
                    if not ble_addr:
                        self.call_ui(self.set_status, "Procurando adaptador BLE...")
                        devs = [d for d in ble_scan() if "OBD" in d[1].upper() or "ELM" in d[1].upper()]
                        if not devs:
                            raise RuntimeError("Nenhum adaptador OBD Bluetooth LE encontrado.")
                        ble_addr = devs[0][0]
                        self.call_ui(self.ble_var.set, f"{devs[0][0]} – {devs[0][1]}")
                    self.call_ui(self.set_status, f"Conectando via BLE a {ble_addr}...")
                    t = BleTransport(ble_addr)
                elif kind == "serial":
                    t = SerialTransport(port, baud)
                elif kind == "wifi":
                    t = TcpTransport(host, tport)
                else:
                    t = SimTransport(renault=(kind == "sim_renault"))
            except Exception as e:
                self.call_ui(self.btn_connect.configure, {"state": "normal"})
                raise RuntimeError(f"Não foi possível abrir o adaptador: {e}")
            try:
                elm = ELM327(t, logger=self.log_from_worker)
                ok = elm.initialize(proto, status=lambda s: self.call_ui(self.set_status, s))
                if ok:
                    self.transport, self.elm = t, elm
                    self.reopen_fn, self.proto_code = open_transport, proto
                    self.supported = elm.supported_pids()
                    self.fail_count = {}
                    self._detect_renault(elm)
                    volt = elm.voltage()
                    mil = elm.mil_status()
            except Exception as e:
                self.transport = self.elm = None
                try:
                    t.close()
                except Exception:
                    pass
                self.call_ui(self.btn_connect.configure, {"state": "normal"})
                raise RuntimeError(f"Falha durante a conexão: {e}")
            if not ok:
                t.close()
                self.call_ui(self.btn_connect.configure, {"state": "normal"})
                self.call_ui(self.set_status, "Falha: a ECU não respondeu")
                raise RuntimeError(
                    "O ELM327 respondeu, mas a ECU do carro não.\n\n"
                    "• Confira se a ignição está ligada.\n"
                    "• Espere ~6 s e tente de novo (a sessão anterior da ECU precisa expirar).\n"
                    "• Adaptadores clone 'v2.1' baratos costumam falhar na linha K;\n"
                    "  prefira um com chip PIC18F25K80 (v1.5).\n"
                    "• Alguns Clio nacionais pré-2007 não seguem o OBD2 padrão.")
            self.call_ui(self._on_connected, volt, mil)

        self.run_job(job)

    def _detect_renault(self, elm):
        """Se a ECU recusa o OBD2 padrão, procura os blocos Renault 21A0..21AF."""
        self.renault_mode = False
        self.renault_lids, self.renault_data, self.renault_stats = [], {}, {}
        if elm.query_pid(0x0C) is not None:
            return
        self.call_ui(self.set_status, "ECU recusa OBD2 padrão – procurando blocos Renault...")
        self.renault_ref = elm.renault_ident()
        for lid in RENAULT_LIDS:
            d = elm.renault_read(lid, quiet=False)
            if d:
                self.renault_lids.append(lid)
                self.renault_data[lid] = d
        self.renault_mode = bool(self.renault_lids)

    def _on_connected(self, volt, mil):
        e = self.elm
        self._set_connected_ui(True)
        self.set_status(f"Conectado – {e.protocol_name}"
                        + ("  –  MODO RENAULT" if self.renault_mode else ""))
        sup = ", ".join(f"{p:02X}" for p in sorted(self.supported)) or "(nenhum)"
        self.info_text.delete("1.0", "end")
        self.info_text.insert("end",
            f"Adaptador : {e.version}\n"
            f"Protocolo : {e.protocol_name} (nº {e.protocol_num})\n"
            f"Bateria   : {volt if volt is not None else '--'} V\n"
            f"PIDs suportados (modo 01): {sup}\n")
        if self.renault_mode:
            lids = ", ".join(f"21{l:02X} ({len(self.renault_data[l])} bytes)" for l in self.renault_lids)
            self.info_text.insert("end",
                f"\nMODO RENAULT: a ECU recusa os dados padrão do OBD2.\n"
                f"Referência da ECU: {self.renault_ref or '--'}\n"
                f"Blocos de dados encontrados: {lids}\n"
                f"Os valores brutos aparecem na aba 'Renault (bruto)' e são gravados no CSV.\n")
            self._rebuild_renault_tree()
        if mil:
            self._show_mil(mil)
        self.nb.select(self.renault_tab if self.renault_mode else 1)
        if getattr(self, "auto_start", False):
            self.auto_start = False
            self.nb.select(1)  # Painel
            if not self.monitoring:
                self.toggle_monitor()

    def disconnect(self):
        self.monitoring = False

        def job():
            if self.transport:
                try:
                    self.transport.close()
                except Exception:
                    pass
            self.transport = self.elm = None
            self.call_ui(self._on_disconnected)
        self.run_job(job)

    def _on_disconnected(self):
        self._on_monitor_stopped()
        self._set_connected_ui(False)
        self.set_status("Desconectado")

    # ---------- monitoramento ----------
    def toggle_monitor(self):
        if self.monitoring:
            self.monitoring = False
            self._on_monitor_stopped()
        else:
            self.monitoring = True
            self.btn_mon.configure(text="■ Parar leitura")
            self.btn_mon2.configure(text="■ Parar leitura")
            self.set_status("Lendo dados ao vivo...")

    def _on_monitor_stopped(self):
        self.btn_mon.configure(text="▶ Iniciar leitura")
        self.btn_mon2.configure(text="▶ Iniciar leitura")
        self.rate_var.set("")

    def _pid_ok(self, pid):
        if self.supported and pid not in self.supported:
            return False
        return self.fail_count.get(pid, 0) < 3

    def _poll_cycle(self):
        if self.renault_mode:
            return self._poll_renault()
        t0 = time.time()
        fast = [p for p in FAST_PIDS if self._pid_ok(p)]
        slow = [p for p in SLOW_PIDS if self._pid_ok(p)]
        pick = []
        for _ in range(min(SLOW_PER_CYCLE, len(slow))):
            pick.append(slow[self.slow_idx % len(slow)])
            self.slow_idx += 1
        for pid in fast + pick:
            if not self.monitoring:
                return
            d = self.elm.query_pid(pid, quiet=True)
            if d is None:
                self.fail_count[pid] = self.fail_count.get(pid, 0) + 1
                continue
            self.fail_count[pid] = 0
            try:
                self.latest[pid] = PID_INFO[pid][3](d)
            except (IndexError, ValueError):
                pass
        if self.cycle % 5 == 0:
            v = self.elm.voltage()
            if v is not None:
                self.latest["volt"] = v
        self.cycle += 1
        self._calc_consumption()
        self.call_ui(self._update_dash, dict(self.latest), time.time() - t0)

    def _poll_renault(self):
        t0 = time.time()
        got = 0
        for lid in self.renault_lids:
            if not self.monitoring:
                return
            d = self.elm.renault_read(lid)
            if d is None:
                continue
            got += 1
            self.renault_data[lid] = d
            for i, b in enumerate(d):
                mn, mx = self.renault_stats.get((lid, i), (b, b))
                self.renault_stats[(lid, i)] = (min(mn, b), max(mx, b))
        self.renault_misses = 0 if got else self.renault_misses + 1
        if self.renault_misses >= 3:
            self.renault_misses = 0
            raise ConnectionError("a ECU parou de responder")
        if self.cycle % 5 == 0:
            v = self.elm.voltage()
            if v is not None:
                self.latest["volt"] = v
        self.cycle += 1
        data = {k: bytes(v) for k, v in self.renault_data.items()}
        if self.capture_phase:
            self.capture_rows.append((time.time(), self.capture_phase, data))
        self.call_ui(self._update_renault, data, self.latest.get("volt"), time.time() - t0)

    def _update_renault(self, data, volt, dt):
        self._update_renault_tree()
        # bateria vem da ECU (RENAULT_MAP); o ATRV do clone lê ~2,2 V a mais, não é usado aqui
        for card, (lid, start, n, conv) in RENAULT_MAP.items():
            d = data.get(lid, b"")
            if len(d) >= start + n and card in self.cards:
                v = conv(int.from_bytes(d[start:start + n], "big"))
                if v is None:
                    continue  # leitura corrompida: mantém o último valor bom
                dec = PID_INFO[card][2] if card in PID_INFO else (2 if card == "inj" else 1)
                self.cards[card].set(f"{v:.{dec}f}")
        if self.monitoring:
            self.rate_var.set(f"ciclo: {dt * 1000:.0f} ms")
        if self.csv_writer:
            if not self.csv_header_done:
                header = ["hora", "nota", "bateria_V"]
                for lid in self.renault_lids:
                    header += [f"{lid:02X}_{i:02d}" for i in range(len(data.get(lid, b"")))]
                self.csv_writer.writerow(header)
                self.csv_header_done = True
            row = [datetime.now().isoformat(timespec="milliseconds"), self.pending_note,
                   "" if volt is None else volt]
            self.pending_note = ""
            for lid in self.renault_lids:
                row += list(data.get(lid, b""))
            self.csv_writer.writerow(row)
            self.csv_file.flush()

    def _calc_consumption(self):
        L = self.latest
        if not all(k in L for k in (0x0B, 0x0F, 0x0C)):
            return
        rpm, map_kpa, iat = L[0x0C], L[0x0B], L[0x0F]
        rho = (map_kpa * 1000) / (287.05 * (iat + 273.15))           # kg/m³
        maf = rho * (CILINDRADA_L / 1000) * (rpm / 120) * EFICIENCIA_VOL * 1000  # g/s
        # em desaceleração com borboleta fechada a injeção corta
        if L.get(0x11, 100) < 1.5 and rpm > 1500:
            maf = 0
        lh = (maf / AFR) * 3600 / DENSIDADE_COMB
        L["cons_lh"] = lh
        spd = L.get(0x0D, 0)
        L["cons_kml"] = (spd / lh) if (spd > 3 and lh > 0.05) else None

    def _update_dash(self, vals, dt):
        for k, var in self.cards.items():
            v = vals.get(k)
            if v is None:
                var.set("--")
            elif isinstance(v, str):
                var.set(v)
            else:
                dec = PID_INFO[k][2] if k in PID_INFO else 1
                var.set(f"{v:.{dec}f}")
        if self.monitoring:
            self.rate_var.set(f"ciclo: {dt * 1000:.0f} ms")
        if self.csv_writer:
            if not self.csv_header_done:
                self.csv_writer.writerow(["hora", "nota"] + [PID_INFO[k][0] if k in PID_INFO else k
                                                             for k in self.cards])
                self.csv_header_done = True
            row = [datetime.now().isoformat(timespec="milliseconds"), self.pending_note]
            self.pending_note = ""
            for k in self.cards:
                v = vals.get(k)
                row.append("" if v is None else (v if isinstance(v, str) else round(v, 3)))
            self.csv_writer.writerow(row)

    def toggle_csv(self):
        if self.log_var.get():
            path = filedialog.asksaveasfilename(
                defaultextension=".csv", filetypes=[("CSV", "*.csv")],
                initialfile=f"clio_log_{datetime.now():%Y%m%d_%H%M%S}.csv")
            if not path:
                self.log_var.set(False)
                return
            self.csv_file = open(path, "w", newline="", encoding="utf-8-sig")
            self.csv_writer = csv.writer(self.csv_file, delimiter=";")
            self.csv_header_done = False  # cabeçalho é escrito na 1ª linha (depende do modo)
            self.set_status(f"Gravando em {path}")
        else:
            self._close_csv()

    def _close_csv(self):
        if self.csv_file:
            self.csv_file.close()
        self.csv_file = self.csv_writer = None

    # ---------- diagnóstico ----------
    def _show_mil(self, mil):
        on, n = mil
        self.mil_var.set(f"Luz de injeção (MIL): {'ACESA' if on else 'apagada'}  –  "
                         f"{n} código(s) armazenado(s)")

    def read_dtcs(self, mode):
        def job():
            codes = self.elm.read_dtcs(mode)
            mil = self.elm.mil_status()
            self.call_ui(self._show_dtcs, codes, mode, mil)
        self.set_status("Lendo códigos de falha...")
        self.run_job(job)

    def _show_dtcs(self, codes, mode, mil):
        self.tree.delete(*self.tree.get_children())
        tipo = "Armazenado" if mode == "03" else "Pendente"
        for c in codes:
            self.tree.insert("", "end", values=(c, tipo, describe_dtc(c)))
        if not codes:
            self.tree.insert("", "end", values=("—", tipo, "Nenhum código encontrado"))
        if mil:
            self._show_mil(mil)
        self.set_status(f"{len(codes)} código(s) {tipo.lower()}(s) encontrado(s)")

    def clear_dtcs(self):
        if not messagebox.askyesno(APP_TITLE,
                "Apagar todos os códigos de falha e apagar a luz de injeção?\n\n"
                "• Faça com a ignição LIGADA e o motor DESLIGADO.\n"
                "• Os dados de prontidão (readiness) também serão zerados.\n"
                "• Se o defeito persistir, o código voltará."):
            return

        def job():
            ok = self.elm.clear_dtcs()
            self.call_ui(self.set_status, "Códigos apagados." if ok else "A ECU não confirmou o apagamento.")
            codes = self.elm.read_dtcs("03")
            mil = self.elm.mil_status()
            self.call_ui(self._show_dtcs, codes, "03", mil)
        self.run_job(job)

    # ---------- terminal ----------
    def send_raw(self):
        cmd = self.cmd_var.get().strip()
        if not cmd:
            return
        if not self.elm:
            self.term_write("[conecte-se primeiro]")
            return
        self.cmd_var.set("")
        self.run_job(lambda: self.elm.send(cmd, timeout=10))

    def _on_close(self):
        self.monitoring = False
        self.stop_event.set()
        self._close_csv()
        if self.transport:
            try:
                self.transport.close()
            except Exception:
                pass
        self.root.destroy()


if __name__ == "__main__":
    import sys
    root = tk.Tk()
    app = App(root)
    # "--conectar": já conecta no adaptador Bluetooth LE e inicia a leitura
    if "--conectar" in sys.argv:
        app.conn_type.set("ble")
        app.auto_start = True
        root.after(500, app.connect)
    root.mainloop()
