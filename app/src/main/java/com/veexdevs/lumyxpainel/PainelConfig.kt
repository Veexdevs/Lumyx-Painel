package com.veexdevs.lumyxpainel

/**
 * ====================================================================================
 * ARQUIVO DE CONFIGURAÇÃO DO PAINEL (Linguagem: Kotlin — arquivo .kt)
 * ====================================================================================
 * Edite este arquivo para adicionar abas, escolher o tipo de cada opção
 * (botao, toggle, check, slide, lista, info, tempoAtivo) e programar o que acontece!
 *
 * COMO ADICIONAR CADA TIPO DENTRO DE UMA ABA:
 *
 * 1) BOTÃO:
 *    botao("Nome do Botão", textoDireita = "OK") { painel ->
 *        painel.vibrar()
 *        painel.aviso("Botão clicado!")
 *    }
 *
 * 2) TOGGLE (Liga / Desliga — ON / OFF):
 *    toggle("Nome do Toggle", padrao = false) { painel, ativo ->
 *        if (ativo) {
 *            painel.aviso("Ligado!")
 *        } else {
 *            painel.aviso("Desligado!")
 *        }
 *    }
 *
 * 3) CHECK (Caixa de marcação — [✓] / [ ]):
 *    check("Nome da Caixinha", padrao = false) { painel, marcado ->
 *        painel.aviso("Marcado: $marcado")
 *    }
 *
 * 4) SLIDE (Barra deslizante numérica):
 *    slide("Sensibilidade", min = 0, max = 200, padrao = 150, sufixo = "") { painel, valor ->
 *        // 'valor' recebe o número atual da barra
 *    }
 *
 * 5) LISTA (Seletor de várias opções):
 *    lista("Modo", opcoes = listOf("Opção 1", "Opção 2", "Opção 3"), padrao = 0) { painel, indice, escolha ->
 *        painel.aviso("Escolheu: $escolha")
 *    }
 *
 * 6) INFO / TEMPO ATIVO:
 *    info("Versão", "1.0.0")
 *    tempoAtivo("Tempo Ativo")
 *
 * ÍCONES DISPONÍVEIS PARA ABAS:
 *    IconeAba.MIRA, IconeAba.SLIDERS, IconeAba.TELA, IconeAba.INFO, IconeAba.RAIO, IconeAba.PODER
 *
 * FUNÇÕES PRONTAS NO OBJETO 'painel':
 *    - painel.aviso("Texto")                -> Mostra mensagem na tela
 *    - painel.vibrar(35)                    -> Vibra o celular levemente
 *    - painel.mostrarMiraCentral(ativo)     -> Mostra/esconde mira fixa no centro da tela
 *    - painel.mudarEstiloMira(indice)       -> 0=Cruz, 1=Ponto, 2=Círculo, 3=Mira T
 *    - painel.mudarTamanhoMira(valor)       -> Ajusta tamanho da mira central (8 a 48)
 *    - painel.mudarCorMira("#FF1724")       -> Muda cor da mira em hexadecimal
 *    - painel.mudarOpacidadePainel(valor)   -> Opacidade da janela (35 a 100)
 *    - painel.mudarTamanhoBolha(valor)      -> Tamanho da bolha RX (46 a 78)
 *    - painel.mudarImanBordas(ativo)        -> Ativa/desativa imã da bolha nas bordas
 *    - painel.centralizarPainel()           -> Centraliza o painel e a bolha
 *    - painel.esconderPainel()              -> Esconde o painel mantendo a bolha RX
 *    - painel.fecharPainel()                -> Fecha o painel e a bolha
 *    - painel.resetarPainel()               -> Reseta todas as opções para o padrão
 *    - painel.abrirLink("https://...")      -> Abre um link no navegador
 *    - painel.abrirApp("com.dts.freefireth")-> Abre um aplicativo instalado pelo pacote
 *    - painel.copiarTexto("texto")          -> Copia texto para a área de transferência
 *    - painel.salvarValor("chave", "valor") -> Salva valor no aparelho
 *    - painel.lerValor("chave", "padrao")   -> Lê valor salvo no aparelho
 * ====================================================================================
 */
object PainelConfig {

    const val TITULO_PAINEL = "Redx Painel • Hack FF"
    const val SUBTITULO_PAINEL = "Versão 1.0.0"

    fun configurarPainel(): List<AbaPainel> = criarPainel {

        // ====================================================================
        // ABA 1: MIRA (Exemplos reais de Toggle, Lista e Slide)
        // ====================================================================
        aba("Mira", IconeAba.MIRA) {

            toggle("Mira Central", padrao = false) { painel, ativo ->
                painel.vibrar()
                painel.mostrarMiraCentral(ativo)
                painel.aviso(if (ativo) "Mira central ativada" else "Mira central desativada")
            }

            lista(
                nome = "Formato Mira",
                opcoes = listOf("Cruz", "Ponto", "Círculo", "Mira T"),
                padrao = 0
            ) { painel, indice, escolha ->
                painel.mudarEstiloMira(indice)
                painel.aviso("Formato: $escolha")
            }

            lista(
                nome = "Cor da Mira",
                opcoes = listOf("Vermelho", "Verde", "Ciano", "Branco"),
                padrao = 0
            ) { painel, indice, _ ->
                val cores = listOf("#FF1724", "#00FF66", "#00E5FF", "#FFFFFF")
                painel.mudarCorMira(cores[indice])
            }

            slide(
                nome = "Tam. Mira",
                min = 8,
                max = 42,
                padrao = 18,
                sufixo = "dp"
            ) { painel, valor ->
                painel.mudarTamanhoMira(valor)
            }
        }

        // ====================================================================
        // ABA 2: SENSI (Exemplos de Slide, Check, Lista e Botão)
        // ====================================================================
        aba("Sensi", IconeAba.SLIDERS) {

            slide(
                nome = "Geral",
                min = 0,
                max = 200,
                padrao = 165,
                sufixo = ""
            ) { painel, valor ->
                painel.salvarValor("sensi_geral", valor.toString())
            }

            slide(
                nome = "Red Dot",
                min = 0,
                max = 200,
                padrao = 150,
                sufixo = ""
            ) { painel, valor ->
                painel.salvarValor("sensi_reddot", valor.toString())
            }

            lista(
                nome = "Modo Perfil",
                opcoes = listOf("Padrão", "Rápido", "Preciso", " Insano"),
                padrao = 0
            ) { painel, _, escolha ->
                painel.vibrar()
                painel.aviso("Perfil: ${escolha.trim()}")
            }

            check("Resposta Rápida", padrao = false) { painel, marcado ->
                painel.vibrar()
                painel.aviso(if (marcado) "Resposta rápida marcada" else "Resposta rápida desmarcada")
            }

            botao("Abrir Free Fire", textoDireita = "IR") { painel ->
                painel.vibrar()
                painel.abrirApp("com.dts.freefireth")
            }
        }

        // ====================================================================
        // ABA 3: TELA (Ajustes da Janela Flutuante e da Bolha RX)
        // ====================================================================
        aba("Tela", IconeAba.TELA) {

            slide(
                nome = "Opacidade",
                min = 35,
                max = 100,
                padrao = 98,
                sufixo = "%"
            ) { painel, valor ->
                painel.mudarOpacidadePainel(valor)
            }

            slide(
                nome = "Bolha RX",
                min = 46,
                max = 78,
                padrao = 56,
                sufixo = "dp"
            ) { painel, valor ->
                painel.mudarTamanhoBolha(valor)
            }

            toggle("Imã nas Bordas", padrao = false) { painel, ativo ->
                painel.mudarImanBordas(ativo)
            }
        }

        // ====================================================================
        // ABA 4: INFO (Tempo ativo e botões de controle)
        // ====================================================================
        aba("Info", IconeAba.INFO) {

            tempoAtivo("Tempo Ativo")

            botao("Centralizar Painel", textoDireita = "OK") { painel ->
                painel.vibrar()
                painel.centralizarPainel()
                painel.aviso("Painel centralizado")
            }

            botao("Resetar Opções", textoDireita = "↺") { painel ->
                painel.vibrar()
                painel.resetarPainel()
                painel.aviso("Opções resetadas!")
            }
        }
    }
}
