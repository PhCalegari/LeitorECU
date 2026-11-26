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

## App Android (APK)
O arquivo pronto para instalar é [`apk/LeitorECU.apk`](apk/LeitorECU.apk). Ele roda no Android 6 ou mais novo e conecta em adaptadores ELM327 **Bluetooth LE**.
- Para instalar, copie o arquivo para o celular, abra e permita "instalar apps desta fonte". O Play Protect pode avisar que o app é desconhecido, porque foi assinado com a chave de teste.
- Tem quatro telas:
  - **Painel** configurável: em **Personalizar**, você escolhe os medidores, a ordem (▲▼) e quais ficam **grandes**. Além dos valores identificados, dá para adicionar **bytes ainda sem nome** que estejam variando (mostrados em valor bruto).
  - **Gráfico** ao vivo de até dois valores, cada um com sua escala, nas janelas de 30, 60 ou 120 s.
  - **Pista** (usa o GPS do celular e a rotação da central):
    - **marcha atual**, calculada pela velocidade ÷ rotação. O app aprende sozinho as relações do câmbio: basta rodar uma vez passando por todas as marchas;
    - **cronômetro 0–100** com parciais 0–60 e 0–80, cálculo entre leituras do GPS e histórico com a rotação máxima;
    - **gráfico de aceleração por marcha** (m/s² × rpm, uma curva por marcha; por padrão só conta as acelerações com o pedal acima de 60 %);
    - **leitura rápida**: com a aba Pista aberta, o app lê só os blocos `21A2` e `21A4` (rotação, corte de injeção, pedal e tempo de injeção) e atualiza os outros a cada 5 leituras. A rotação chega cerca de 2,5 vezes mais rápido;
    - **descoberta automática da velocidade na central**: compara cada byte com a velocidade do GPS enquanto você dirige. Ao achar o byte, passa a usar a velocidade da central na marcha e no consumo;
    - **gravação da sessão** em CSV (`Downloads/LeitorECU/sessao_*.csv`): velocidade do GPS, aceleração, marcha e todos os valores da central.
  - **Diagnóstico**: ler e apagar códigos de falha. Também tem **informações completas da central**: referência, Vdiag, fornecedor, hardware, programa, versão e calibrações, além de tentar ler o VIN (2181), a rede (2182) e a rastreabilidade (2184).
  - **Bytes**: os blocos `21A0` a `21A4` (✓ = já identificado) e a captura guiada, que salva em `Downloads/LeitorECU/`.
- O botão **Demo** simula a central e serve para ver as telas sem o carro.
- O código-fonte (Kotlin, sem dependências externas) está em [`android/`](android/). Para compilar, use JDK 17 e Android SDK 34, criando `android/local.properties` com `sdk.dir=...`, e rode `gradlew assembleDebug`.

## Abas (app para PC)
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
| Pedal do acelerador (PR126) | `21A3` 15 | × 100/256 % | ✅ 14 % com o pé fora (manual: 14 %) |
| Pedal pistas 1 e 2 (PR206/PR207) | `21A2` 20 e 21 | × 100/256 % | ✅ 14 % em repouso (manual: 14 %) |
| Bateria | `21A2` 15 | × 0,032 + 8 V | ✅ 13,7 V ligado, 12,6 V desligado |
| Correção da riqueza (PR035) | `21A2` 16 | valor bruto, 128 = neutro | ✅ o manual mostra o valor bruto (100–130); fica em 128 no corte de combustível |
| Temperatura da água | `21A2` 13 | × 0,625 − 40 °C | 🟡 a escala confere com o manual (sensor com defeito: −40 / 120 °C = byte 0 / 255). Falta confirmar o byte: a ventoinha liga a 99 °C |
| Temperatura do ar | `21A2` 14 | × 0,625 − 40 °C | 🟡 ~46 °C (o sensor fica no coletor quente). Confirmar com o motor frio: tem que ser igual à da água |
| Borboleta medida (PR017) | `21A3` 18 | × 100/256 % | 🟡 12,1–12,9 % em marcha lenta (manual: 12 %); fica em 0 com o motor parado |
| Injeção ativa (corte na desaceleração) | `21A2` 2, bit 7 | 1 = injetando, 0 = corte | 🟡 vai a 0 ao soltar o pedal acima de ~1.000 rpm |
| Regulação de marcha lenta ativa | `21A2` 2, bit 6 | 1 = ativa | 🟡 1 em marcha lenta, 0 ao acelerar |
| Pé levantado | `21A2` 0, bit 6 (cópia em `21A3` 0) | 1 = pedal solto | 🟡 240 com o pé solto, 128 pisando, mesmo com o motor desligado (corrigido: antes estava em `21A0` 0, que fica sempre 0) |
| Pé no fundo | `21A2` 0, bit 1 | 1 = pedal no fundo | 🟡 131 com o pedal a 89 % nas capturas |
| Marcha lenta desejada (PR041) | `21A2` 17–18 | valor direto (rpm) | ✅ 752: exatamente o valor do manual ("PR041 = 752 r.p.m.") |
| Pedal solto aprendido (PR008) | `21A3` 16 | × 100/256 % | ✅ 14,1 % (manual: 14 %) |
| Cópias da rotação | `21A3` 9–10 e `21A4` 10–11 | valor direto (rpm) | ✅ mesmo valor de `21A2` 7–8 |
| Sonda lambda traseira (PR010) | `21A2` 12 | × 9,76 mV | 🟡 488 mV desligado e 462 mV em marcha lenta (manual: ~500 / 400 mV) |
| Sinal de detonação (PR209) | `21A4` 16 | bruto | 🟡 0 com o motor desligado; sobe com a rotação |
| Velocidade (PR018) | descoberta automática | — | 🔍 o app Android acha o byte sozinho comparando com o GPS (aba Pista) |

As capturas de exemplo (CSV) estão em `capturas/`. A tensão lida pelo próprio adaptador (`ATRV`) marca cerca de 2,2 V a mais do que a real.

## Consumo (app Android)
O consumo é **calculado pelo tempo de injeção** lido da central:
`L/h = (rpm/2 × 4 injetores) × vazão do injetor × (tempo de injeção − tempo morto)`.
Com o corte de injeção ativo, o consumo vai a zero. O km/L usa a velocidade do GPS (aba Pista).
A vazão real do injetor do D4D não é publicada, então o app começa com 110 cc/min e 0,8 ms de tempo morto e se corrige pelo **abastecimento**: em **Painel → Consumo**, zere a viagem com o tanque cheio e, ao completar de novo, digite os litros que entraram. O app ajusta o fator automaticamente.
Medidores disponíveis: consumo (L/h), consumo instantâneo (km/L), média da viagem, combustível gasto e distância.

## Aba Aprender (app Android)
O app vai aprendendo sozinho, sem IA: compara a central com coisas que ele já sabe.

**Ensinar o app (eventos):** com o motor ligado e o carro parado, toque em um item (Ar-condicionado, Ventoinha, Farol, Freio, Vidro térmico, Embreagem), espere a contagem e, no "AGORA", ligue ou desligue aquilo. O app compara os bits e bytes da central 3 s antes e 2–6 s depois e guarda o que mudou junto. Depois de 2 ou mais vezes (em pelo menos 75 % delas) o bit fica "aprendido" e pode ser mostrado no Painel.

Bytes que já mostraram mais de 8 valores diferentes são tratados como valores analógicos e não entram como candidatos (os bits deles mudam sempre que a rotação muda). A **ventoinha** é marcada sem contagem: toque assim que ouvir ela ligar ou desligar.

**Ventoinha × água:** a temperatura da água quando a ventoinha liga (marcada à mão, pelo bit aprendido ou estimada por uma queda da bateria em lenta) é comparada com os 99 °C esperados, para conferir a escala da água.

**Partida a frio:** se o carro ficou parado 3 h ou mais, a primeira leitura compara a água com o ar (os dois devem estar na temperatura ambiente).

**Adaptações:** bytes que ficam fixos durante a viagem mas mudam de um dia para outro (prováveis valores aprendidos pela central) são listados com o histórico dos últimos 30 dias.

**Saúde do motor:** o app guarda a mediana de cada viagem (lenta quente, injeção na lenta, correção da mistura, temperatura em uso, bateria com motor ligado, bateria na partida, tempo para esquentar). Depois de 3 viagens ele avisa no Painel quando algo foge do normal do seu carro, com as causas mais prováveis. Use "Zerar o normal" depois de um conserto. O modo Demo não ensina nada ao app.

## Mapas de ponto e de injeção (app Android, aba Gráfico)
Tabela no formato dos editores de calibração: rotação nas linhas (500, 650, 750, 900, 1150 … 6400, crescendo para baixo) e pressão do coletor nas colunas (200 … 1000 mbar). Cada célula mostra a média do avanço (ou do tempo de injeção) que a central está usando de verdade naquela faixa, com 2 casas e cores suaves (verde-água = pouco, salmão/vermelho = muito). Traços vermelhos nos cabeçalhos e a célula vermelha mostram onde o motor está agora. Células sem leitura, mas com leituras dos dois lados, aparecem estimadas em itálico e mais claras.

É **só leitura**: o mapa é montado com o que a central faz enquanto o carro roda. O app não grava nada na central (remap/reprogramação não é feito: o adaptador clone por Bluetooth não regrava a SIM 32 com segurança e mexer no ponto pode causar detonação).

## Visual e modo diurno/noturno (app Android)
Visual de ferramenta de injeção: tudo reto, Painel em blocos pintados pela escala de calor do próprio valor (azul baixo → vermelho alto), barra superior com status e menu ⋮ (Conectar, Demo, Pausar, Personalizar painel, Consumo, Conectar sozinho, Modo diurno/noturno). Modos: **Automático** (segue o modo escuro do celular), **Diurno** (branco) e **Noturno** (preto). A troca não derruba a conexão com o carro.

## Velocidade, marchas e lambda (app Android)
- **Velocidade da central**: `21A3` byte 11 × 1 (km/h), achada comparando com o GPS. Na aba Pista ela é o número grande (atualiza a cada ~0,3 s, sem o atraso do GPS); o GPS aparece embaixo para comparar. O velocímetro do carro marca a mais de fábrica: o botão **Igualar ao painel do carro** ajusta só a exibição (consumo e km usam a velocidade real).
- **Marchas** pelas relações de fábrica do câmbio do Clio 1.0 16V Hi-Power: 1ª 4,09 · 2ª 2,24 · 3ª 1,39 · 4ª 1,03 · 5ª 0,79, diferencial 4,36 (conferidas com os dados do carro, erro até ~3 %). O app só ajusta sozinho a escala do pneu.
- **Lambda (bloco da sonda)**: λ estimado pela média de 3 s da sonda de banda estreita (confiável só perto de 1,00, de ~0,94 a ~1,06), AFR para a gasolina brasileira (13,2:1 no ideal), LEDs de 0,90 a 1,10 (vermelho rica, verde ideal 0,97–1,03, azul pobre) e etiqueta IDEAL / RICA / POBRE / CORTE / MOTOR FRIO.

## Conexão automática (app Android)
Com a opção **Conectar automaticamente** marcada (padrão), o app conecta sozinho ao abrir, procurando o último adaptador usado. Se o carro estiver desligado, tenta de novo a cada ~10 s; se a conexão cair, fica tentando até o carro voltar. Toque em **Desconectar** para parar as tentativas. Na primeira vez é preciso tocar em **Conectar** para liberar a permissão de Bluetooth.

## Mistura e sonda lambda (app Android)
A sonda original é **de banda estreita**: indica só se a mistura está rica (~0,8–0,9 V) ou pobre (~0,1–0,2 V) em relação ao ideal, e não a relação ar/combustível exata. Por isso o app mostra:
- **Sonda lambda (estado)**: "Oscilando ✓", "Rica", "Pobre", "Parada ⚠" ou "Motor frio", olhando os últimos 12 s e ignorando os cortes de injeção (em que a sonda cai naturalmente para ~0,05–0,1 V);
- **Indicador no bloco da sonda (Painel)**: barra de LEDs de 0 a 1 V (azul = pobre, verde = 0,45 V ideal, vermelho = rica) e etiqueta RICA / POBRE / CORTE (corte = pé fora do acelerador), com a média de 30 s ao lado. Com o motor quente é normal alternar entre rica e pobre várias vezes por segundo; problema é ficar parado num lado ou a média passar de ±10 %;
- **Mistura**: média de 30 s da correção da riqueza (PR035, com 128 = neutro), em %. Positivo quer dizer que a central está adicionando combustível (o motor tende a ficar pobre). Até ±10 % aparece "OK", de 10 a 20 % "tendência", e acima de 20 % um alerta.

## Códigos de falha
Os dois apps mostram, para cada código OBD (P0xxx/U0xxx), o **código Renault (DF)** e a descrição do quadro de falhas oficial da SIM32 D4 Vdiag 04 (manual 17B). São 50 códigos. Por exemplo, P0627 aparece como "DF008 – circuito de comando do relé da bomba de combustível".

## Observações
- O app só **lê** dados e códigos de falha. Ele não mexe em imobilizador nem em programação da central.
- A conversão dos bytes é engenharia reversa a partir de medições próprias. Os itens marcados 🟡 ainda precisam ser confirmados.
