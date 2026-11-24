# Portarretratos digital

App para la tablet **Digiland DL701Q (Android 4.4)**. Pasa las fotos de la tarjeta SD
y les pone encima un marco según la fecha: cumpleaños, feriados de Costa Rica,
temporadas (todo diciembre navideño) o el marco del mes.

## 1. Preparar la tarjeta SD

Copie la carpeta `Portarretratos` a la raíz de la tarjeta SD:

```
Portarretratos/
  fotos/       ← sus fotos (.jpg/.png). Puede usar subcarpetas.
  marcos/      ← los marcos PNG con el centro transparente
  fechas.txt   ← cumpleaños, feriados y ajustes
```

## 2. Instalar la app

1. Copie `Portarretratos.apk` a la tablet (en la misma SD sirve).
2. En la tablet: **Ajustes → Seguridad → Orígenes desconocidos** (activar).
3. Abra el APK con el administrador de archivos e instálelo.
4. Abra **Portarretratos**. Se abre solo cada vez que se enciende la tablet.

- **Tocar la pantalla**: pasa a la siguiente foto y muestra información (qué marco usa y por qué).
  Si la pantalla está en modo noche, la enciende 1 minuto.
- **Botón atrás**: cierra la app.

Los cambios en la SD (fotos, marcos, `fechas.txt`) se leen solos; no hace falta reinstalar.

## Subir fotos desde el teléfono (WiFi de la casa)

1. En `fechas.txt` de la tarjeta, quite el `#` de la línea `# pin = 0000` y ponga su PIN.
2. Toque la pantalla del portarretratos: abajo de la información aparece
   **📱 Desde el teléfono: http://192.168.x.x:8080** (la tablet debe estar en el WiFi).
3. Abra esa dirección en el navegador del teléfono y escriba el PIN.
4. Ahí puede **subir fotos**, **quitar fotos**, **subir marcos** y **editar las fechas**.

Notas:
- Solo funciona con el teléfono en el mismo WiFi que la tablet.
- Las fotos subidas desde el teléfono se guardan en la tarjeta, en
  `Android/data/cr.portarretratos/files/fotos` (Android 4.4 no deja a las apps escribir en otro lado).
  **Si se desinstala la app, Android borra esa carpeta.** Actualizar la app (instalar encima) no borra nada.
- Las fotos de la carpeta `Portarretratos/fotos` no se pueden borrar desde el teléfono en Android 4.4:
  se **ocultan** del portarretratos (la lista está en `ocultas.txt` de esa misma carpeta).
- Si cambia las fechas desde el teléfono, la app usa la versión más nueva entre la del teléfono y la de la tarjeta.

## 3. Los marcos y las fotos

**Cómo hacer los marcos con ChatGPT: vea `MARCOS.md`** (prompt y lista completa).

- PNG horizontal con el **centro verde puro (#00FF00)** o transparente; la app abre el hueco sola.
- El nombre del archivo es el que se escribe en `fechas.txt`, sin `.png`.
- Para que varios marcos se turnen: `navidad.png`, `navidad2.png`, `navidad3.png`…
- Si para una fecha no hay marco todavía, se usa el del mes y, si no, `normal.png`.
- **Fotos verticales**: se ven completas en el hueco del marco con la misma foto difuminada
  a los lados. Las horizontales llenan el hueco. Se cambia con `ajuste =` en `fechas.txt`.

Los 4 marcos que vienen ahora (`normal`, `noviembre`, `navidad`, `cumple`) son **de prueba**.

## 4. fechas.txt

```
cumple 11/08 = Papá                         ← cumpleaños (día/mes = nombre)
cumple 03/05 = Emanuel | cumple-emanuel     ← con marco propio
fecha 25/12 = navidad | ¡Feliz Navidad!     ← fecha = marco | texto opcional
fecha SEMANA_SANTA = semana-santa           ← se calcula sola cada año
fecha DIA_DEL_PADRE = dia-padre             ← tercer domingo de junio
temporada 01/12-31/12 = navidad             ← rango de fechas
mes noviembre = noviembre                   ← marco del mes
segundos = 20                               ← tiempo de cada foto
noche = 22:30-06:00                         ← pantalla negra de noche
```

Prioridad: **cumpleaños → fecha → temporada → mes → normal**.
Si la tablet tiene la fecha mal, todo falla: revise **Ajustes → Fecha y hora**.
