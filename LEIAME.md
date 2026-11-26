# LeitorECU – OBD2 / ELM327 para Renault Clio 1.0 16V (Siemens SIM 32)

App para Windows, em Python e Tkinter, que lê os dados da central de injeção usando um adaptador ELM327. O adaptador pode ser Bluetooth LE, USB/Bluetooth clássico (porta COM) ou Wi-Fi.

Foi feito e testado num **Renault Clio II 1.0 16V Hi-Power 2005 (Brasil)**, motor D4D, central **Siemens SIM 32**. Essa central **recusa os dados padrão do OBD2** (modo 01) e só entrega dados ao vivo pelos comandos próprios da Renault (KWP2000, serviço `21`). Por isso o app tem um **modo Renault**, cujo mapa de bytes foi descoberto comparando medições do carro com os valores de referência do manual da Renault.

## Como usar
1. Instale o Python 3.8 ou mais novo.
2. Dê dois cliques em `iniciar.bat`. Na primeira vez, ele instala o `pyserial` e o `bleak`.
   - `conectar_clio.bat` abre o app, conecta no adaptador Bluetooth LE e já começa a ler.
3. Ligue o ELM327 no conector OBD e ligue a ignição.
4. Na aba **Conexão**, escolha o tipo de adaptador e clique em **Conectar**:
   - **Bluetooth LE (ex.: OBDBLE):** não precisa parear no Windows. Clique em **Procurar**, escolha o adaptador e conecte. Feche antes o app do celular, porque o adaptador aceita uma conexão por vez.
   - **Bluetooth clássico:** pareie no Windows (PIN 1234 ou 0000) e escolha a porta COM **de saída**.
   - **USB:** se a porta não aparecer, instale o driver CH340 ou FTDI.
   - **Wi-Fi:** conecte o PC à rede do adaptador (padrão 192.168.0.10:35000).
   - **Simuladores:** "Simulador" (OBD2 padrão) e "Simulador Renault" servem para testar sem o carro.

## Abas
- **Painel:** valores convertidos (rotação, pressão do coletor, temperaturas, avanço, injeção, sonda lambda, bateria e outros). Pode gravar tudo em CSV.
- **Renault (bruto):** mostra cada byte dos blocos `21A0`–`21A4` com mínimo e máximo. Os bytes que variaram ficam em amarelo. Tem botões de anotação e a **captura guiada**, uma tela cheia com avisos para acelerar e soltar, que salva o resultado em `capturas/`.
- **Diagnóstico:** lê códigos de falha armazenados (03) e pendentes (07), mostra a luz de injeção e apaga os códigos (04).
- **Terminal:** para enviar comandos AT e de diagnóstico à mão.

O app reconecta sozinho se a conexão cair.

## Protocolo (testado no carro)
- Linha K, **ISO 14230-4 KWP2000 com início rápido**, 10.400 bps. Funciona no protocolo Automático ou no 5 do ELM. Os protocolos 4 e 3 não conectam.
- Central no endereço `0x7A`, ferramenta em `0xF1`.
- Ao reconectar, espere cerca de 6 s, porque a sessão anterior precisa expirar (o tempo máximo de espera entre mensagens, P3max, é 5 s).
- Identificação (`2180`): ref. 8200539152, hardware 8200400246, programa 00D4, versão 5000, versão de diagnóstico 04, fornecedor 001 (Siemens).
- Modo 01 → `7F 01 12` (recusado). Os modos 03 e 07 funcionam.
- Com o clone OBDBLE v2.1, os blocos `21A5` em diante voltam com lixo. O app lê só `21A0` a `21A4`.

## Mapa dos dados (SIM 32, programa D4, Vdiag 04)
Os índices são do byte de dados, depois do `61 xx`. As referências vêm do manual Renault 17B "Inyección SIM 32 / D4 / Vdiag 04, 08".

| Dado | Bloco / bytes | Conversão | Situação |
|---|---|---|---|
| Rotação | `21A2` 7–8 | valor direto (rpm) | ✅ marcha lenta ~750 (manual: 752) |
| Pressão do coletor | `21A0` 12–13 | × 0,0578125 + 103 mbar | ✅ ~355 mbar em marcha lenta (manual: ~350), pressão atmosférica com o motor desligado |
| Avanço de ignição | `21A4` 15 | × 0,375 − 23,625 ° | ✅ −23,6° desligado (manual: −24°), 0–2° em marcha lenta |
| Tempo de injeção | `21A4` 13–14 | × 4 µs | ✅ ~4,0 ms em marcha lenta (manual: 3,5–4) |
| Sonda lambda montante | `21A2` 11 | × 9,76 mV | ✅ oscila 0,1–0,9 V em malha fechada |
| Pedal / borboleta | `21A3` 15 | 36 = 0 %, 229 = 100 % | ✅ |
| Bateria | `21A2` 15 | × 0,032 + 8 V | ✅ 13,7 V ligado, 12,6 V desligado |
| Correção da mistura | `21A2` 16 | (x − 128) / 128 × 100 % | 🟡 provável (fica em 128 no corte de combustível) |
| Temperatura da água | `21A2` 13 | × 0,625 − 40 °C | 🟡 provisória. Confirmar: a ventoinha liga a 99 °C |
| Temperatura do ar | `21A2` 14 | × 0,625 − 40 °C | 🟡 provisória. Confirmar com o motor frio |
| Velocidade | ? | ? | ❌ falta uma captura andando |

As capturas de exemplo (CSV) estão em `capturas/`. A tensão lida pelo próprio adaptador (`ATRV`) marca cerca de 2,2 V a mais do que a real.

## Observações
- O app só **lê** dados e códigos de falha. Ele não mexe em imobilizador nem em programação da central.
- A conversão dos bytes é engenharia reversa a partir de medições próprias. Os itens marcados 🟡 ainda precisam ser confirmados.
