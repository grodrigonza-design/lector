# Cómo hacer los marcos con ChatGPT

## Reglas (lo importante)

1. **Horizontal.** Pida formato horizontal panorámico (16:9). Si ChatGPT no puede, el
   horizontal normal (3:2, 1536×1024) también sirve: la app lo ajusta a la pantalla.
2. **Centro verde puro.** El hueco del centro debe ser un rectángulo de **verde puro
   (#00FF00), plano**, sin sombras ni texturas. La app lo vuelve transparente sola.
   (No pida "fondo transparente": ChatGPT a veces dibuja cuadritos grises falsos.)
3. **Hueco grande.** El borde decorativo debe ocupar solo un 8–12 % de cada lado,
   para que el hueco sea grande y las fotos se vean bien.
4. **Sin letras.** ChatGPT se equivoca escribiendo en español y el texto taparía las
   fotos. La app escribe el mensaje ("¡Feliz cumpleaños, Emanuel!", "¡Feliz Navidad!").
5. **Guarde como PNG** con el nombre exacto de la tabla (ej. `navidad.png`) en la
   carpeta `marcos` de la SD.

## Prompt base

Copie esto y cambie solo la parte de **[TEMA]**:

> Crea un marco decorativo para un portarretratos digital, formato horizontal
> panorámico 16:9. El centro debe ser un rectángulo grande con esquinas redondeadas,
> relleno de color verde puro #00FF00, totalmente plano, sin sombras, sin degradados
> y sin nada encima. El borde decorativo ocupa solo los bordes de la imagen (alrededor
> del 10 % de cada lado) y no invade el rectángulo verde. Tema del borde: **[TEMA]**.
> Estilo ilustrado, alegre, colores vivos, alta calidad. No incluyas ningún texto,
> letras ni números.

Para que todos combinen, hágalos en la misma conversación y después del primero diga:
*"Ahora otro con el mismo estilo y las mismas medidas, tema: [TEMA]"*.

Si el centro no sale verde liso (sale con dibujos encima o con sombra), pida:
*"El rectángulo central debe ser verde #00FF00 completamente liso, sin nada encima."*

## Lista de marcos (≈ 33)

### Cumpleaños (la app agrega el nombre)
| Archivo | Para | [TEMA] |
|---|---|---|
| `cumple-abuelos.png` | Abuela Fanny, Abuelo Hernán, Abuela Cheli | cumpleaños elegante y cálido: rosas y flores en tonos crema, dorado y vino, velas, encaje, estilo clásico y tierno |
| `cumple-tios.png` | los tíos y tías | fiesta de cumpleaños para adultos: globos metálicos, confeti, serpentinas, copas, tonos turquesa, fucsia y dorado |
| `cumple-primos.png` | los primos y primas | fiesta infantil y juvenil: globos de colores, piñata, dulces, estrellas, regalos, colores vivos y alegres |
| `cumple.png` / `cumple2.png` | los demás (Emmanuel, Gustavo, Raquel, Christopher, José, Diego) | globos, confeti y un pastel / elegante en dorado y azul marino |

Cada grupo puede tener variantes que se turnan: `cumple-tios2.png`, `cumple-primos2.png`…
Si el marco de un grupo todavía no existe, la app usa `cumple.png`.

### Fechas especiales
| Archivo | Fecha | [TEMA] |
|---|---|---|
| `anio-nuevo.png` | 1 ene | fuegos artificiales dorados, copas de champán, serpentinas |
| `san-valentin.png` | 14 feb | corazones rojos y rosados, rosas, sobres de amor |
| `semana-santa.png` | Semana Santa | palmas, lirios blancos, tonos morado y dorado, ambiente sereno |
| `juan-santamaria.png` | 11 abr | colores de la bandera de Costa Rica, antorcha encendida, laureles |
| `dia-trabajador.png` | 1 may | herramientas, granos de café, campo costarricense |
| `dia-padre.png` | 3er domingo jun | corbatas, herramientas, tonos azules |
| `guanacaste.png` | 25 jul | árbol de guanacaste, sabana, carreta típica, sombrero de sabanero |
| `virgen-angeles.png` | 2 ago | flores blancas y celestes, angelitos, velas, tonos dorados |
| `dia-madre.png` | 15 ago | rosas y flores en tonos pastel, corazones suaves |
| `persona-negra.png` | 31 ago | cultura afrocaribeña de Limón: palmeras, tambores, patrones coloridos |
| `dia-nino.png` | 9 sep | juguetes, globos, cometas, colores primarios |
| `independencia.png` | 15 sep | faroles de colores, bandera de Costa Rica, antorcha de la independencia |
| `halloween.png` | 31 oct | calabazas sonrientes, murciélagos, dulces, estilo infantil no aterrador |
| `abolicion-ejercito.png` | 1 dic | palomas de la paz, ramas de olivo, bandera de Costa Rica |
| `navidad.png` | 24–25 dic y todo diciembre | pinos, flor de pascua, luces y regalos |
| `navidad2.png` | se turna con navidad | pesebre sencillo, estrellas, velas, tonos dorados |
| `fin-de-anio.png` | 31 dic | reloj a medianoche, fuegos artificiales, uvas, serpentinas |

### Temporada
| Archivo | Cuándo | [TEMA] |
|---|---|---|
| `mes-patrio.png` | 1–15 sep | faroles, banderitas de Costa Rica, flores azul, blanco y rojo |

### Un marco por mes
| Archivo | [TEMA] |
|---|---|
| `enero.png` | verano: playa, sol, palmeras, conchas |
| `febrero.png` | flores rosadas y corazones suaves |
| `marzo.png` | árboles de cortez amarillo en flor, cielo azul |
| `abril.png` | mangos, frutas tropicales, mariposas |
| `mayo.png` | inicio de las lluvias: hojas verdes, gotas, ranitas |
| `junio.png` | selva verde, helechos, colibríes |
| `julio.png` | tucanes, guacamayas, flores tropicales |
| `agosto.png` | orquídeas, flores suaves |
| `septiembre.png` | lluvia, paraguas de colores, arcoíris |
| `octubre.png` | perezosos, ranas de colores, selva |
| `noviembre.png` | viento y cometas (papalotes) en el cielo, hojas |
| (diciembre usa `navidad`) | |

### Por defecto (si no hay nada más)
| Archivo | [TEMA] |
|---|---|
| `normal.png` | marco de madera clásico y elegante |
| `normal2.png` | flores tropicales y hojas |

## Variantes
Puede agregar más marcos del mismo tema numerándolos: `enero2.png`, `navidad3.png`…
La app los va turnando foto por foto.

## ¿Salió mal un marco?
Mándeselos a Claude: puede quitar el centro, recortarlos o dejarlos del tamaño exacto.
