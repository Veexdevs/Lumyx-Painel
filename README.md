# Redx Painel

Aplicativo Android (APK) **Redx Painel** nas cores **Preto e Vermelho** com a logo **RX**, **bolha flutuante sempre ativa** e **arquivo único de configuração (`PainelConfig.kt`)** para personalizar todas as abas e ações.

## Onde editar os Botões, Toggles, Slides e Listas

Abra o arquivo:
`app/src/main/java/com/veexdevs/lumyxpainel/PainelConfig.kt` *(Linguagem: **Kotlin**)*

Dentro de qualquer `aba("Nome", IconeAba.MIRA) { ... }`, basta escrever:

```kotlin
// 1. Botão simples
botao("Meu Botão", textoDireita = "OK") { painel ->
    painel.vibrar()
    painel.aviso("Clicou no botão!")
}

// 2. Toggle (ON / OFF)
toggle("Meu Toggle", padrao = false) { painel, ativo ->
    painel.aviso(if (ativo) "Ativado" else "Desativado")
}

// 3. Check ([✓] / [ ])
check("Minha Caixinha", padrao = false) { painel, marcado ->
    painel.aviso("Marcado: $marcado")
}

// 4. Slide (Barra numérica)
slide("Meu Slide", min = 0, max = 200, padrao = 150, sufixo = "") { painel, valor ->
    // 'valor' contém o número escolhido
}

// 5. Lista de opções
lista("Minha Lista", opcoes = listOf("Opção A", "Opção B", "Opção C"), padrao = 0) { painel, indice, escolha ->
    painel.aviso("Selecionado: $escolha")
}

// 6. Texto fixo ou Cronômetro
info("Versão", "1.0.0")
tempoAtivo("Tempo Ativo")
```

Sempre que você salvar alterações no `PainelConfig.kt` pelo GitHub, o workflow **Actions > Build APK** gera automaticamente o novo `Redx-Painel.apk`.

## Como Baixar o APK no GitHub

1. Acesse a aba **Actions** deste repositório e clique na execução mais recente de **Build APK**.
2. Em **Artifacts**, baixe **`Redx-Painel-APK`** (extraia o `.zip` para obter o `Redx-Painel.apk`).
3. *(Na branch `main`)* O APK também fica publicado na aba **Releases**.
