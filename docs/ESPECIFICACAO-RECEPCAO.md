# Especificação funcional — Recepção do MARKYS

Esta especificação registra os requisitos informados para modernização da Recepção. A implementação deverá respeitar o isolamento multi-clínica, as permissões por perfil e a auditoria. Os cadastros de pacientes, tipos de atendimento, salas e exames serão compartilhados com os módulos autorizados, inclusive o painel profissional.

## 1. Cadastro de paciente

A ação **Adicionar paciente** abre o formulário completo em uma única tela. O botão principal será **Cadastrar paciente** (em edição, reaproveitar a mesma tela e preencher os dados existentes).

Campos:
- Nome completo.
- CPF.
- Data de nascimento.
- Idade calculada automaticamente a partir da data de nascimento; não exibir idade como campo editável.
- Convênio.
- Cargo ou função.
- Inclusão de documento em PDF.

Os campos devem ter rótulos claros, validação, máscaras adequadas e mensagens de erro junto ao campo. Validar CPF e data de nascimento. O upload deve aceitar somente PDF validado pelo conteúdo/MIME, com limite de tamanho configurável e armazenamento privado, sem expor URL pública. A ficha PDF deverá incluir os dados do cadastro.

## 2. Tipo de exame e atendimento

No mesmo formulário, incluir seleção de tipo de atendimento:
- Admissional
- Demissional
- Periódico
- Retorno
- Mudança de riscos
- Particular
- Pontual
- Consulta Ocupacional
- Concurso

Os tipos existentes aparecem em menu suspenso. Ao lado, disponibilizar ações **Adicionar** e **Editar**, com campo em branco para inclusão/edição. Os itens são configuráveis por clínica e não devem ser duplicados por variações de maiúsculas/espaços.

## 3. Salas e consultórios

No mesmo formulário, disponibilizar menu suspenso de sala/consultório, com estas dez opções iniciais:
1. RAIO X
2. COLETA / LABORATÓRIO
3. AUDIOMETRIA
4. EXAMES COMPLEMENTARES
5. CONSULTA MÉDICA
6. COLETA DE PCCU
7. PROCEDIMENTOS
8. TRIAGEM
9. ULTRASSOM
10. FISIOTERAPIA

Ao lado do menu, ações **Adicionar** e **Editar**, com campo em branco para cadastro/edição. As salas são configuráveis por clínica. Não excluir sala que já tenha exames ou atendimentos vinculados; permitir inativação preservando histórico.

## 4. Exames específicos e vínculo com sala

O formulário deverá permitir selecionar vários exames específicos, agrupados por menus suspensos:

### RAIO-X
- Tórax PA
- Tórax Perfil
- Tórax OIT
- Tórax AP
- Coluna Lombar
- Coluna Lombo Sacra
- Coluna Total
- Coluna Cervical
- Coluna Torácica
- Coluna Dorsal
- Ombros
- Ombro D
- Ombro E
- Mãos
- Mão D
- Mão E
- Joelhos
- Joelhos D
- Joelhos E
- Punhos
- Punhos D
- Punhos E

### COLETA / LABORATÓRIO
- Coleta de sangue
- Hemograma C.
- Glicose
- Creatinina
- Tipagem S.
- Beta
- Colesterol
- Colinesterase
- Cultura F
- EPF
- EAS
- Acetona
- Teste Ergonométrico
- Acetona na Urina
- Ácido Mandélico
- Hemoglobina Glicada
- Lipidograma
- Chumbo na Urina
- Micológico
- Toxicológico
- Triglicerídeos
- TGO
- TGP
- Chumbo sanguíneo
- Hepatite HBsAg
- Hepatite Anti-HBs
- Hepatite Anti-HCV
- Hepatite B - ANTI-HBC TOTAL
- Metil etil cetona
- Ácido metil-hipúrico
- Ácido hipúrico
- Ácido delta-aminolevulínico

### AUDIOMETRIA
- Audiometria
- Audiometria Tonal
- Audiometria Vocal
- Audiometria Ocupacional
- Audiometria Concurso
- Teste Otoneurológico
- Teste Vestibular

### EXAMES COMPLEMENTARES
- EEG
- ECG
- Espirometria
- Acuidade
- Acuidade com percepção de cores
- Av Psico
- Mapa 24h
- Holter 24h
- Risco Cirúrgico

Ao lado dos menus, disponibilizar **Adicionar** e **Editar**, com campo em branco. Incluir botão de atalho **ASO**, seguindo o comportamento visual/funcional de referência do FAVMED, a ser implementado sem copiar código ou depender daquele sistema.

Cada exame específico deve ter vínculo obrigatório com uma sala/consultório. Ao cadastrar exame novo, selecionar uma sala existente. Se a sala necessária ainda não existir, permitir cadastrá-la no mesmo fluxo e então vinculá-la. O sistema deve impedir salvar exame sem sala válida. Os exames e vínculos são configuráveis por clínica; alterações futuras não podem modificar retroativamente o histórico dos atendimentos já registrados.

## 5. Fichas, dashboard e compartilhamento

Manter:
- **Fichas e dashboard**.
- Cadastros compartilhados com o painel profissional, respeitando permissões.
- **Exportar dashboard (CSV)**, mantendo o padrão visual e de funcionamento dos demais botões de exportação.

## 6. Chamada e mídia da recepção

Remover textos/informações explicativas sobre voz. Disponibilizar apenas uma configuração objetiva com três modos:
- **Chamar**: pronunciar o nome do paciente.
- **Sinal sonoro**: usar o mesmo padrão de sinal sonoro de referência do FAVMED.
- **Desativado**: exibir somente a mudança visual, sem voz ou som.

Disponibilizar **Enviar foto ou vídeo** com somente o botão e o campo de seleção/arquivo, sem textos adicionais de orientação na interface.

## 7. Tela Pacientes na fila

Ações e comportamentos:
- **Limpar atendidos**: solicitar PIN de confirmação antes de executar. O PIN informado pelo usuário (1605) não deve ser embutido no JavaScript, no HTML ou no repositório. A validação deverá ocorrer no servidor com segredo configurado de forma segura, armazenado como hash ou secret de ambiente, com limitação de tentativas e auditoria. A limpeza deve afetar somente registros elegíveis da clínica ativa e preservar histórico/auditoria.
- **Visualizar Ficha**: gerar/abrir PDF contendo todos os dados cadastrados do paciente e os dados da ficha pertinentes, com acesso autorizado.
- **Editar Informações**: abrir a mesma tela completa de cadastro, preenchida com todos os dados, permitindo edição conforme permissões.
- **ASO Permanente**: ao duplo clique, alternar para verde e exibir **ASO REALIZADO**. A mudança deve ser persistida no servidor, ter data/usuário responsável e permitir auditoria; não depender apenas de estado visual no navegador.

## 8. Regras transversais e segurança

- Todo dado de paciente, atendimento, sala, exame, mídia e documento deve pertencer à clínica ativa.
- Aplicar autorização no servidor e RLS no PostgreSQL; não confiar somente em ocultação de botões.
- Registrar auditoria de alterações relevantes, especialmente edição de cadastro, exclusão/limpeza, alteração de status ASO e acesso/geração de documentos.
- Preservar registros históricos: inativar cadastros referenciados em vez de apagá-los.
- Usar transações para salvar paciente, atendimento, exames selecionados e vínculos.
- Não colocar dados clínicos em CSV público ou URL aberta; exportações devem exigir autenticação e autorização.
- Não habilitar dados reais antes de concluir testes de isolamento, autorização, armazenamento privado, backup e homologação.

## 9. Critérios de aceite

1. Um único formulário cadastra e edita paciente, atendimento, sala e exames.
2. Idade é calculada corretamente e atualizada quando a data de nascimento muda.
3. Menus de tipo, sala e exames carregam dados da clínica e permitem adicionar/editar sem sair do formulário.
4. Todo exame selecionado fica associado a uma sala existente.
5. O cadastro aparece de forma consistente na Recepção e no painel profissional.
6. PDF da ficha contém todos os dados permitidos e é acessível somente a usuários autorizados.
7. Exportação CSV respeita filtros e permissões.
8. Os três modos de chamada executam exatamente o comportamento definido.
9. A limpeza da fila exige confirmação segura no servidor e não apaga histórico clínico.
10. A edição reutiliza o mesmo formulário e preserva trilha de auditoria.
11. O duplo clique de ASO Permanente persiste o status e apresenta ASO REALIZADO em verde.
12. Testes automatizados comprovam isolamento entre clínicas e autorização por perfil.
