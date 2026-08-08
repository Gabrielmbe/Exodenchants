# FabledCustomEnchants — Proyecto Maven

Plugin puente de encantamientos personalizados para **Fabled 5**.
Servidor: **Paper 1.21.x → 26.2** · Compilación: **JDK 21+ y Maven 3.9+**.

---

## 1. Requisitos para compilar

| Herramienta | Versión mínima | Verificación |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9 | `mvn -version` |

> El servidor 26.x corre sobre Java 25, pero compilar con `release 21`
> produce un jar compatible con todo el rango 1.21 → 26.2.

## 2. Compilar

```bash
cd FabledCustomEnchants
mvn clean package
```

El jar queda en:

```
target/FabledCustomEnchants-1.0.0.jar
```

### 2b. Nota sobre la dependencia de Fabled

Fabled se publica en **Maven Central**, así que no necesita ningún
repositorio propio; el `pom.xml` usa `1.0.4-R0.56`, la última versión
disponible en Central. Si tu servidor corre un build más nuevo descargado
de Spigot (ej: `1.0.4-R0.73`) y quieres compilar exactamente contra él,
instálalo en tu repositorio local y ajusta `<version>` en el `pom.xml`:

```bash
mvn install:install-file \
  -Dfile=Fabled-1.0.4-R0.73.jar \
  -DgroupId=studio.magemonkey \
  -DartifactId=fabled \
  -Dversion=1.0.4-R0.73 \
  -Dpackaging=jar
```

(Ajusta `-Dversion` a la versión exacta de tu jar y espeja el cambio en el
`pom.xml` si difiere.)

## 3. Instalar en el servidor

1. Copia `target/FabledCustomEnchants-1.0.0.jar` a `plugins/`.
2. Copia TODOS los .yml de `fabled-skills/` (las 8 skills de efectos)
   a `plugins/Fabled/dynamic/skill/` y ejecuta `/fabled reload`.
3. Reinicia el servidor. El plugin genera automáticamente su carpeta
   `plugins/FabledCustomEnchants/` con `config.yml`, `modules/`, `pools/`,
   `books/`, `enchants/` y `guis/`.
4. (Opcional) Instala **Vault** + un plugin de economía para que la tienda cobre.

## 4. Comandos

| Comando | Descripción | Permiso |
|---|---|---|
| `/encantos` | Abre el menú principal (Dark Mode) | — |
| `/encantos reload` | Recarga toda la configuración | `fce.admin` |
| `/encantos give <enchant> <nivel> [exito] [ruptura]` | Da un libro (ej: `give vampirismo 2 75 10`) | `fce.admin` |

## 5. Arquitectura

```
InventoryClickEvent (libro en cursor + click sobre equipo)
        │  lee SOLO Data Components (fe_id, fe_level, fe_success, fe_destroy)
        ▼
Tirada 1-100 ≤ {exito} ──✔──► escribe ench_<id> en el PDC del ítem
        │                      + línea de Lore estética (MiniMessage)
        ✘                      + sonido block.anvil.use
        ▼
Libro consumido + partículas de ruptura + 2ª tirada contra {ruptura}
        ▼
FabledBridge sincroniza la skill (FE_Vampirismo nivel N) al empuñar el ítem
        ▼
Fabled 5 ejecuta el efecto con su trigger PHYSICAL_DAMAGE
y su escalado nativo (value-base + value-scale × nivel)
```

- **Efectos** → 100 % Fabled 5 (skills dinámicas en `fabled-skills/`).
- **Aplicación, libros, tienda y GUIs** → este plugin (Java, Paper API).
- **Datos** → siempre en `minecraft:custom_data` (PDC); el Lore nunca se parsea.

## 6. Añadir un encantamiento nuevo

1. Crea `enchants/mi_enchant.yml` (copia `vampirismo.yml` como base).
2. Crea la skill `FE_MiEnchant` en `plugins/Fabled/dynamic/skill/`
   (o con el editor web de Fabled) y apúntala en `fabled-skill`.
3. `/encantos reload` — sin recompilar: el jar es genérico.

## 7. Catálogo, categorías y modo inspección

El sistema trae **28 encantamientos** en tres tiers, organizados por tipo de
ítem. Todo es dinámico: cada `enchants/*.yml` aparece solo en el catálogo, en
su categoría y en el pool de su tier.

| Comando | Descripción | Permiso |
|---|---|---|
| `/encantos` | Menú principal | — |
| `/encantos catalogo` | Catálogo completo (paginado, 21 por página) | — |
| `/encantos categorias` | Filtra por espadas, hachas, picos, mazas, arcos y armadura | — |
| `/encantos polvos` | Tienda de Polvos Mágicos | — |
| `/encantos inspect` | Activa/desactiva el modo inspección | `fce.admin` |
| `/encantos check` | Inspecciona el ítem que tienes en la mano | `fce.admin` |
| `/encantos give <enchant> <nivel> [exito] [ruptura]` | Entrega un libro concreto | `fce.admin` |
| `/encantos dust <polvo> [cantidad]` | Entrega polvos mágicos | `fce.admin` |
| `/encantos reload` | Recarga configuración | `fce.admin` |

**Modo inspección (admin):** actívalo y haz click sobre una espada, pico, hacha,
arco, maza o pieza de armadura en cualquier inventario. Se abre una GUI con
**solo** los encantamientos compatibles con ese ítem, y un click en cualquier
entrada te entrega su libro. No interfiere con la aplicación de libros ni con
los polvos: el inspector solo actúa con el cursor vacío.

### Reparto por tier

- **Común (11):** Llamarada, Vigor, Paso Ligero, Desgarro, Cegar, Veta, Topo, Caparazón, Aguante, Amortiguar, Flecha Helada
- **Raro (10):** Vampirismo, Congelación, Toxina, Aturdir, Sangrado, Furia, Desarme, Fortuna Arcana, Contragolpe, Tiro Certero
- **Legendario (7):** Zeus, Colosal, Verdugo, Sismo, Barrena, Bastión, Titán

## 8. Polvos Mágicos

Consumibles de precio elevado que **reducen el % de ruptura** de un libro antes
de aplicarlo. Cada polvo es su propio `dusts/<id>.yml`; la tienda
(`guis/dust_shop.yml`) lee precios y reducciones desde ahí, así que nunca se
desincronizan.

| Polvo | Reduce | Precio |
|---|---|---|
| Polvo Menor | −5 % | \$45,000 |
| Polvo Arcano | −10 % | \$120,000 |
| Polvo Celestial | −20 % | \$320,000 |
| Polvo Primordial | −100 % | \$850,000 |

**Uso:** toma el polvo con el cursor y haz click sobre un libro de
encantamiento. El efecto es **garantizado** (no hay tirada) y consume una
unidad. Se pueden acumular varios polvos sobre el mismo libro hasta el suelo
definido en `config.yml` → `limits.min-destroy-rate` (por defecto 0 %).

El nuevo % viaja en el Data Component `fe_destroy` del libro y el Lore se
reescribe solo para reflejarlo.

## 9. Orden de las GUIs

Todas las interfaces comparten la misma retícula: marco negro, relleno gris,
acentos de color solo en las cuatro esquinas y filas centradas.

| GUI | Distribución |
|---|---|
| Menú principal | fila 2 (11·13·15) tiendas y filtros · fila 4 (29·31·33) consulta y admin · 40 cerrar |
| Tienda de libros | fila 3 (20·22·24) tiers · fila 4 (29·31·33) accesos · 40 balance · 49 volver |
| Tienda de polvos | fila 3 (19·21·23·25) polvos · 31 guía · 40 balance · 49 volver |
| Categorías | fila 2 (11-15) armas y herramientas · fila 3 (19·21·23·25) armadura · 40 volver |
| Catálogo / Inspector | filas 2-4 entradas (21 por página) · 48·49·50 navegación |

## 11. Esencias de Éxito

Segunda familia de consumibles, complementaria a los Polvos. Cada archivo de
`dusts/` declara su `mode`:

- `mode: destroy` → **resta** puntos al % de **ruptura** (los Polvos).
- `mode: success` → **suma** puntos al % de **éxito** (las Esencias).

| Esencia | Aumenta éxito | Precio |
|---|---|---|
| Esencia de Fortuna | +10 % | \$150,000 |
| Esencia Mayor | +20 % | \$400,000 |
| Esencia Divina | +45 % | \$900,000 |

Se usan igual que los Polvos: tómalas con el cursor y haz click sobre el libro.
Efecto garantizado, una unidad por uso, acumulables hasta el techo definido en
`config.yml` → `limits.max-success-rate` (100 % por defecto).

Con un libro Divino base (5-20 % de éxito, 35-60 % de ruptura), la combinación
de Esencias y Polvos es la única vía realista para aplicarlo sin perder el ítem.

## 12. Notas de compatibilidad y correcciones

**Detección del motor de efectos.** La comprobación es ahora perezosa y
tolerante al orden de carga: un `softdepend` no garantiza que Fabled se habilite
antes que este plugin, así que el motor se consulta en cada uso y el aviso solo
se emite si sigue ausente **después** de que el servidor termine de cargar.
También se reconocen los nombres históricos del motor (ProSkillAPI, SkillAPI).

Si el aviso persiste, comprueba que el jar de Fabled esté en `plugins/` y que
aparezca en verde en `/plugins`. Las GUIs, libros y polvos funcionan sin él; lo
único que no se ejecuta son los efectos en combate.

**Duplicación visual al inspeccionar.** Abrir un inventario desde dentro del
propio evento de click deja el ítem clicado en un estado intermedio y el cliente
lo dibuja duplicado. El inspector ahora cancela el click, fuerza un refresco del
inventario y abre la GUI en el tick siguiente, cuando el estado ya está
consolidado.

## 13. Correcciones: inspector de un solo uso y diagnóstico

**Inspector.** La inspección ahora es de un solo uso: se consume en el momento
en que abre la GUI. Al cerrar el menú y volver a hacer click sobre el mismo
ítem, el inventario se comporta con normalidad en lugar de reabrir el
inspector en bucle. Para inspeccionar otro ítem, reactívala con
`/encantos inspect` (o el botón del menú) o usa `/encantos check` con el ítem
en la mano.

**Efectos que "no hacen nada" (picos, etc.).** La causa habitual es que las
skills de `fabled-skills/` no están cargadas en Fabled. Herramientas nuevas
para detectarlo al instante:

- `/encantos debug` (admin): muestra si el motor está detectado, cuántas
  skills están cargadas (48/48 si todo va bien), cuáles faltan, y el estado
  del ítem que tienes en la mano (encantamiento → nivel de skill activo).
- Si llevas equipado un encantamiento cuya skill no existe en Fabled, la
  consola lo avisa una sola vez con la instrucción exacta para corregirlo.
- Sincronización reforzada: además de los eventos (cambiar de ítem en mano,
  intercambiar manos con F, cerrar inventario, entrar al servidor) hay un
  respaldo automático cada 5 segundos.

Checklist si un encantamiento no surte efecto:

1. `/encantos debug` → ¿motor detectado? ¿48/48 skills?
2. Si faltan skills: copia TODOS los .yml de `fabled-skills/` a
   `plugins/Fabled/dynamic/skill/` y ejecuta `/fabled reload`.
3. Sostén el ítem encantado y repite `/encantos debug`: debe mostrar
   "skill nivel N". A partir de ahí, el trigger (golpear, minar, matar,
   recibir daño) ejecuta el efecto.

## 14. Motor de efectos NATIVO (corrección definitiva)

**El problema.** Los efectos dependían de que Fabled tuviera la skill cargada
Y de que el nivel de esa skill estuviera registrado en los datos del jugador.
Si faltaba cualquiera de las dos piezas, el encantamiento quedaba aplicado en el
ítem pero no hacía nada.

**La solución.** El plugin trae ahora su propio motor de efectos. Lee el nivel
directamente de los Data Components del equipo y ejecuta el efecto sobre los
eventos de Bukkit. Consecuencia práctica: **si el ítem lleva el encantamiento,
el efecto se dispara** — sin skills, sin niveles, sin `/fabled reload`.

Cada `enchants/*.yml` declara su efecto de forma legible:

```yaml
effects:
  trigger: attack          # attack · defend · kill · mine · land
  chance-base: 15
  chance-scale: 5          # probabilidad = base + scale x (nivel - 1)
  actions:
    - type: damage         # damage · heal · potion · fire · lightning
      target: victim       #          push · sound · particle
      value-base: 4.0
      value-scale: 2.0
      true-damage: true
```

Los 48 encantamientos anteriores se tradujeron a este formato conservando
exactamente sus números. Fabled pasa a ser **opcional**: si está instalado, se
sigue sincronizando por compatibilidad; si no, todo funciona igual.

## 15. Encantamientos de herramienta (mecánicas inéditas)

17 encantamientos nuevos para picos y hachas con mecánicas que no existen como
componentes en ningún sistema de skills, porque manipulan bloques, botín,
experiencia y durabilidad.

### Picos (9)

| Encantamiento | Tier | Efecto |
|---|---|---|
| Vetadora | Legendario | Extrae la veta completa (4 + 4 bloques por nivel) |
| Perforadora | Mítico | Rompe área 3x3 en la cara mirada |
| Fundición Arcana | Legendario | Funde el mineral al instante (60 % + 15 %) |
| Telequinesis | Raro | Drops y XP directos al inventario |
| Prosperidad | Mítico | Multiplica el botín (x2 + x1 por nivel) |
| Sabiduría | Raro | Multiplica la experiencia (x2 + x1) |
| Zahorí | Legendario | Revela minerales cercanos con destellos |
| Autoforja | Mítico | Repara la herramienta sola (20 durabilidad/nivel) |
| Detonador | Divino | Acumula cargas → detonación 5x5 |

### Hachas (8)

| Encantamiento | Tier | Efecto |
|---|---|---|
| Talador | Legendario | Tumba el árbol completo (20 + 20 troncos) |
| Reforestador | Común | Replanta un brote en el tocón |
| Aserradero | Raro | Tablones extra por tronco (2 + 2) |
| Resinador | Raro | Resina, panal o palos al talar |
| Descortezador | Legendario | Tronco adicional (20 % + 15 %) |
| Podadora | Común | Despeja el follaje (radio 2 + 1) |
| Recolector Arbóreo | Raro | Madera y XP directas al inventario |
| Furia del Leñador | Mítico | Acumula cargas → tajo en área |

**Compatibilidad con protecciones.** Cada rotura en cadena lanza su propio
evento de rotura, así que los plugins de regiones, claims y anti-grief pueden
vetarla igual que un golpe normal. Hay además un techo de seguridad de 220
bloques por acción para que una veta enorme no afecte al rendimiento.

Combinaciones recomendadas: Vetadora + Fundición Arcana + Telequinesis (minado
industrial), Talador + Reforestador + Recolector Arbóreo (tala sostenible).

## 16. Progresión, competencia y economía viva

Cinco sistemas nuevos orientados a retención y a que el plugin se sienta premium.

### Racha de suerte (anti-mala-suerte)

Cada fallo consecutivo del **mismo tier** suma un bono oculto al % de éxito del
siguiente intento de ese tier, y se reinicia al acertar. Configurable en
`config.yml` → `luck` (por defecto +3 % por fallo, techo +25 %). El jugador ve
el bono acumulado en el chat, así que una mala racha deja de ser frustrante y
pasa a ser una promesa.

### Combos de set

Llevar 3 encantamientos distintos del mismo tier equipados (manos + armadura)
concede un bono pasivo, definido en `modules/set_combos.yml`:

| Conjunto | Bono |
|---|---|
| Raro ×3 | Velocidad I |
| Legendario ×3 | Velocidad I + Prisa I |
| Mítico ×3 | Velocidad II + Prisa I + Resistencia I |
| Divino ×3 | Velocidad II + Prisa II + Resistencia II + Regeneración I |

Solo se aplica el combo de mayor rango y se retira solo al dejar de cumplirlo.
Progreso visible con `/encantos combos`.

### Mercado negro

`/encantos mercado` — 4 ofertas que rotan cada 6 horas (configurable) con
descuentos del 15-45 % y libros cuyo **% de éxito ya viene mejorado**. La
rotación es determinista: todos los jugadores ven el mismo catálogo y este
cambia solo al cumplirse el plazo, sin necesidad de reiniciar. El icono de cada
oferta es el libro real que vas a recibir, con sus números exactos.

### Ranking de encantadores

`/encantos top` — puntuación por encantamiento aplicado con éxito: Común 1 pt,
Raro 3, Legendario 8, Mítico 20, Divino 50. Los datos se guardan en
`stats.yml` con escritura agrupada y asíncrona (no toca disco en cada click).

### Anuncios globales

Solo se anuncia lo que merece atención — basta cumplir **una** de las tres
condiciones de `config.yml` → `announce`: tier valioso (Legendario+), libro
caro (90k+), o gesta improbable (aplicado con ≤ 20 % de éxito). Un Común nunca
hace ruido; un Divino siempre. Cada anuncio genera envidia y vende libros.

### PlaceholderAPI

Se registra solo si PlaceholderAPI está presente. Identificador `fce`:

```
%fce_score%              %fce_rank%              %fce_applied%
%fce_applied_<tier>%     %fce_streak_<tier>%     %fce_luck_<tier>%
%fce_combo%              %fce_combo_count_<tier>%
%fce_market_time%        %fce_top_name_1%        %fce_top_score_1%
%fce_total_enchants%
```

### Comandos nuevos

| Comando | Descripción |
|---|---|
| `/encantos mercado` | Mercado negro rotativo |
| `/encantos top` | Ranking de encantadores |
| `/encantos combos` | Progreso de tus combos de set |

## 17. GUIs premium, 6 encantamientos por ítem y brillo encantado

**Rediseño visual.** Todas las interfaces comparten ahora un estilo "dark mode"
más rico: fondo negro, marco gris, acentos de color solo en esquinas y franjas
separadoras, títulos con ✦, y cada Lore enmarcado entre reglas ▬ con viñetas ❘
y una llamada a la acción ➜. El menú principal pasa a dos filas simétricas de
cuatro accesos, y el ranking muestra un podio real (oro, hierro, cadena) con los
nombres y puntuaciones de los siete primeros.

**Hasta 6 encantamientos por ítem.** `config.yml` → `limits.max-enchants-per-item`
sube de 4 a 6.

**Brillo encantado.** Al aplicar un encantamiento, el ítem recibe el brillo
nativo (Data Component `enchantment_glint_override`, 1.20.5+), de modo que un
pico o un hacha se ven claramente encantados aunque no lleven ningún
encantamiento vanilla. Además, el Lore recibe una cabecera decorativa una sola
vez. Ambos ajustes viven en la sección `cosmetics` de `config.yml`:

```yaml
cosmetics:
  glint: true
  lore-header: "<dark_gray>▬▬▬ <gradient:#7B2CBF:#C77DFF>ᴇɴᴄᴀɴᴛᴀᴍɪᴇɴᴛᴏꜱ</gradient> <dark_gray>▬▬▬"
```

## 18. Lore profesional y progresión hacia los tiers altos

**Lore enmarcado por tier.** La sección de encantamientos del ítem se
reconstruye completa en cada aplicación: cabecera ◤▬▬ ✦ ᴇɴᴄᴀɴᴛᴀᴍɪᴇɴᴛᴏꜱ ✦ ▬▬◥,
una línea por encanto precedida por el símbolo de su tier (◆ Común/Raro,
✦ Legendario, ❖ Mítico, ✵ Divino), ordenadas de Común a Divino, y pie de
cierre. Todo configurable en `config.yml` → `cosmetics`.

**Libros de alto tier difíciles de verdad.**

| Tier | Precio | Requisito de compra |
|---|---|---|
| Mítico | \$95,000 → **\$350,000** | **150 pts** de encantador |
| Divino | \$275,000 → **\$1,500,000** | **600 pts** de encantador |

Los puntos son los del ranking (`ranking.points`): comprar un Divino exige
haber aplicado con éxito el equivalente a ~12 Legendarios o ~30 Míticos. El
requisito se declara por GUI con `min-score` en la acción `buy-book`, así que
puedes ajustarlo o quitarlo sin tocar código.

**Mercado negro ponderado.** Las ofertas ya no eligen encantamiento de forma
uniforme sino por el **peso del tier** (`pools/tiers.yml`): un libro Divino
aparece con peso 1 frente a 60 del Común — verlo en el mercado es un
acontecimiento, y como el mercado no exige puntos, es la única vía de acceso
temprano (a precio de descuento sobre una base 5 veces mayor).
