package cr.portarretratos;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String CARPETA = "Portarretratos";
    private static final long REESCANEO_MS = 30 * 60 * 1000L;
    private static final int FADE_MS = 1200;

    private final Handler ui = new Handler();
    private final ExecutorService fondo = Executors.newSingleThreadExecutor();

    private final ImageView[] fotoV = new ImageView[2];
    private int frente = 0;
    private ImageView marcoV;
    private TextView textoV, infoV, avisoV, fbV, fechaV, horaV, pieV;
    private Typeface letraFecha, letraHora;
    private View nocheV;

    // Estado (solo se toca desde el hilo de fondo)
    private File base;
    private Carpetas carpetas;
    private Servidor servidor;
    private Facebook facebook;
    private final Clima clima = new Clima();
    private volatile boolean retroceder = false;
    private final java.util.Map<String, Long> fechasFotos = new java.util.HashMap<String, Long>();
    private final java.util.Map<String, File> marcosArch = new java.util.HashMap<String, File>();
    private Agenda agenda = new Agenda();
    private long configMod = -2, ultimoEscaneo = 0;
    private final List<File> fotos = new ArrayList<File>();
    private final List<String> marcos = new ArrayList<String>();
    private int idxFoto = 0, idxMarco = 0;
    private String marcoCargado;
    private Bitmap marcoBmp;
    private Rect marcoVentana;
    private final int[] marcoTexto = new int[4];
    private boolean activa;

    private final Runnable tick = new Runnable() { public void run() { siguiente(); } };
    private final Runnable ocultarInfo = new Runnable() { public void run() { infoV.setVisibility(View.GONE); } };

    private static class Paso {
        Bitmap foto, marco; String texto, info, error, pie; boolean noche, textoArriba; int segundos = 20;
        Rect ventana; int ancho, alto, textoDesde, textoHasta, textoAncho, textoAnchoArriba;
        boolean verFecha = true, verHora = true, hora24;
        String orientacion;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            fotoV[i] = new ImageView(this);
            fotoV[i].setScaleType(ImageView.ScaleType.FIT_XY);
            fotoV[i].setAlpha(0f);
            raiz.addView(fotoV[i], lleno());
        }
        marcoV = new ImageView(this);
        marcoV.setScaleType(ImageView.ScaleType.FIT_XY);
        raiz.addView(marcoV, lleno());

        textoV = new TextView(this);
        textoV.setTextColor(Color.WHITE);
        textoV.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        textoV.setShadowLayer(8, 0, 2, Color.BLACK);
        textoV.setGravity(Gravity.CENTER);
        textoV.setPadding(dp(18), dp(6), dp(18), dp(8));
        GradientDrawable pastilla = new GradientDrawable();
        pastilla.setColor(0x66000000);
        pastilla.setCornerRadius(dp(24));
        textoV.setBackgroundDrawable(pastilla);
        textoV.setVisibility(View.GONE);
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        raiz.addView(textoV, lt);

        // Fecha (arriba, letra manuscrita) y hora (abajo), dentro del hueco del marco
        letraFecha = fuente("fonts/DancingScript-Bold.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        letraHora = fuente("fonts/PlayfairDisplay-Bold.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD));
        pieV = etiqueta(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        raiz.addView(pieV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        fechaV = etiqueta(letraFecha);
        raiz.addView(fechaV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        horaV = etiqueta(letraHora);
        raiz.addView(horaV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        avisoV = mensaje(16);
        avisoV.setGravity(Gravity.CENTER);
        raiz.addView(avisoV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        infoV = mensaje(12);
        infoV.setBackgroundColor(0xCC000000);
        raiz.addView(infoV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        // Recuadro con el código para conectar Facebook (abajo a la izquierda, letra grande)
        fbV = mensaje(17);
        fbV.setBackgroundColor(0xE0202A44);
        fbV.setTextColor(Color.WHITE);
        fbV.setLineSpacing(0, 1.15f);
        FrameLayout.LayoutParams lf = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lf.leftMargin = lf.bottomMargin = dp(10);
        raiz.addView(fbV, lf);

        nocheV = new View(this);
        nocheV.setBackgroundColor(Color.BLACK);
        nocheV.setVisibility(View.GONE);
        raiz.addView(nocheV, lleno());

        // Tocar: siguiente foto + información. Tocar de noche: enciende 1 minuto.
        final android.view.GestureDetector gestos = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(android.view.MotionEvent e) { mostrarInfo = true; siguiente(); return true; }
                    @Override public boolean onFling(android.view.MotionEvent a, android.view.MotionEvent b, float vx, float vy) {
                        float dx = b.getX() - a.getX();
                        if (Math.abs(dx) < dp(60) || Math.abs(dx) < Math.abs(b.getY() - a.getY())) return false;
                        if (dx > 0) retroceder = true; // deslizar a la derecha = foto anterior
                        siguiente();
                        return true;
                    }
                });
        raiz.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) { return gestos.onTouchEvent(e); }
        });
        setContentView(raiz);
        pantallaCompleta();

        carpetas = new Carpetas(this);
        servidor = new Servidor(this, carpetas);
        servidor.iniciar();
        Red.preparar(this);
        facebook = new Facebook(carpetas);
        servidor.facebook = facebook;
        servidor.instalador = new Servidor.Instalador() {
            public void instalar(final File apk) {
                ui.post(new Runnable() {
                    public void run() {
                        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                        i.setDataAndType(android.net.Uri.fromFile(apk), "application/vnd.android.package-archive");
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                });
            }
        };
        facebook.iniciar();
        clima.iniciar();
    }

    private volatile boolean mostrarInfo = true; // al abrir, mostrar la dirección para el teléfono

    @Override protected void onResume() { super.onResume(); activa = true; siguiente(); ui.postDelayed(reloj, 15000); }
    @Override protected void onPause() { super.onPause(); activa = false; ui.removeCallbacks(tick); ui.removeCallbacks(reloj); }
    @Override protected void onDestroy() { super.onDestroy(); fondo.shutdownNow(); if (servidor != null) servidor.detener(); if (facebook != null) facebook.detener(); clima.detener(); }

    @Override
    public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco) pantallaCompleta();
    }

    private void pantallaCompleta() {
        int f = View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) {
            f |= 0x00001000 /* IMMERSIVE_STICKY */ | 0x00000200 /* LAYOUT_HIDE_NAVIGATION */
                    | 0x00000400 /* LAYOUT_FULLSCREEN */ | 0x00000100 /* LAYOUT_STABLE */;
        }
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    // ------------------------------------------------------------------ ciclo

    private volatile long despiertoHasta = 0;
    private boolean incluidosListos = false;
    private volatile String ultimaFalla;

    private void siguiente() {
        ui.removeCallbacks(tick);
        if (!activa) return;
        final boolean info = mostrarInfo;
        mostrarInfo = false;
        if (info && nocheV.getVisibility() == View.VISIBLE) despiertoHasta = System.currentTimeMillis() + 60000;
        View decor = getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 17) getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            else dm = getResources().getDisplayMetrics();
            w = dm.widthPixels; h = dm.heightPixels;
        }
        final int ancho = w, alto = h;
        fondo.execute(new Runnable() {
            public void run() {
                final Paso p = preparar(ancho, alto, info);
                ui.post(new Runnable() { public void run() { aplicar(p); } });
            }
        });
    }

    /** Hilo de fondo: busca la carpeta, lee configuración, elige y decodifica. */
    private Paso preparar(int ancho, int alto, boolean info) {
        Paso p = new Paso();
        p.ancho = ancho; p.alto = alto; p.ventana = new Rect(0, 0, ancho, alto); p.textoHasta = alto; p.textoAncho = ancho; p.textoAnchoArriba = ancho;
        try {
            if (base == null || !base.isDirectory()) { base = buscarCarpeta(); carpetas.base = base; configMod = -2; }
            if (!incluidosListos) { carpetas.copiarIncluidos(); incluidosListos = true; }
            recargarSiHaceFalta();
            String web = Servidor.direccion();
            if (fotos.isEmpty()) {
                p.error = "¡Hola! Todavía no hay fotos.\n\n"
                        + (web != null
                            ? "Desde el teléfono (conectado al mismo WiFi),\nabra en el navegador:\n\n" + web
                              + (agenda.pin == null ? "\n\nAhí mismo podrá crear su PIN." : "")
                            : "Conecte la tablet al WiFi para subir fotos desde el teléfono.")
                        + "\n\nO copie fotos en la carpeta " + CARPETA + "/fotos\n(tarjeta SD o memoria interna).";
                p.segundos = 10;
                return p;
            }
            p.segundos = agenda.segundos;
            p.orientacion = agenda.orientacion;
            p.textoArriba = agenda.textoArriba;
            p.verFecha = agenda.mostrarFecha;
            p.verHora = agenda.mostrarHora;
            p.hora24 = agenda.hora24;

            Calendar ahora = Calendar.getInstance();
            if (agenda.esNoche(ahora) && System.currentTimeMillis() > despiertoHasta) {
                p.noche = true;
                p.segundos = 60;
                return p;
            }

            boolean vertical = alto > ancho;
            Agenda.Resultado r = agenda.decidir(ahora, marcos, vertical);
            p.texto = r.texto;
            if (!r.archivos.isEmpty()) {
                String nombre = r.archivos.get(idxMarco++ % r.archivos.size());
                String clave = nombre + "@" + ancho + "x" + alto + (r.girar ? "@girado" : "");
                if (!clave.equals(marcoCargado)) {
                    marcoBmp = null;
                    File archMarco = marcosArch.get(nombre);
                    Bitmap crudo = archMarco == null ? null : decodificar(archMarco, ancho, alto, true);
                    if (crudo != null && r.girar) {
                        // Tablet parada y no hay versión vertical del marco: girar el horizontal 90°.
                        Matrix giro = new Matrix();
                        giro.postRotate(90);
                        Bitmap girado = Bitmap.createBitmap(crudo, 0, 0, crudo.getWidth(), crudo.getHeight(), giro, true);
                        if (girado != crudo) crudo.recycle();
                        crudo = girado;
                    }
                    if (crudo != null) {
                        marcoBmp = Bitmap.createScaledBitmap(crudo, ancho, alto, true).copy(Bitmap.Config.ARGB_8888, true);
                        if (crudo != marcoBmp) crudo.recycle();
                        marcoVentana = Fotos.prepararMarco(marcoBmp, marcoTexto);
                    }
                    marcoCargado = clave;
                }
                p.marco = marcoBmp;
            } else { marcoCargado = null; marcoBmp = null; }
            p.ventana = marcoBmp != null ? marcoVentana : new Rect(0, 0, ancho, alto);
            p.textoDesde = marcoBmp != null ? marcoTexto[0] : 0;
            p.textoHasta = marcoBmp != null ? marcoTexto[1] : alto;
            p.textoAncho = marcoBmp != null ? marcoTexto[2] : ancho;
            p.textoAnchoArriba = marcoBmp != null ? marcoTexto[3] : ancho;

            // Si una foto no se puede abrir, probar con las siguientes (hasta 8) en vez de dejar el marco vacío.
            File foto = null;
            Bitmap original = null;
            if (retroceder && !fotos.isEmpty()) { idxFoto = ((idxFoto - 2) % fotos.size() + fotos.size()) % fotos.size(); }
            retroceder = false;
            for (int intento = 0; intento < 8 && original == null && !fotos.isEmpty(); intento++) {
                if (idxFoto >= fotos.size()) { idxFoto = 0; ultimoEscaneo = 0; recargarSiHaceFalta(); }
                if (fotos.isEmpty()) break;
                foto = fotos.get(idxFoto++ % fotos.size());
                original = decodificar(foto, ancho, alto, false);
                if (original == null) ultimaFalla = foto.getName() + " (" + Diagnostico.tipo(foto) + ")";
            }
            if (original != null) {
                p.foto = Fotos.componer(original, p.ventana, ancho, alto, agenda.ajuste);
                original.recycle();
                if (agenda.mostrarPie) p.pie = pieDeFoto(foto, ahora);
            }
            if (p.texto == null) p.texto = agenda.proximoCumple(ahora); // "En 3 días: cumpleaños de…"

            if (info) {
                SimpleDateFormat f = new SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", new Locale("es"));
                StringBuilder sb = new StringBuilder();
                sb.append(f.format(ahora.getTime())).append('\n')
                  .append("Marco: ").append(r.motivo).append(marcoCargado != null ? " → " + marcoCargado : "").append('\n')
                  .append("Foto ").append(idxFoto).append(" de ").append(fotos.size()).append(": ").append(foto.getName()).append('\n')
                  .append("Marcos: ").append(marcos.size()).append('\n')
                  .append("Carpeta: ").append(base != null ? base.getAbsolutePath() : "(no está la tarjeta)");
                if (web != null) sb.append("\n📱 Desde el teléfono: ").append(web).append(agenda.pin == null ? "  (cree su PIN ahí)" : "");
                if (facebook.configurado()) {
                    sb.append("\nFacebook: ").append(facebook.conectado()
                            ? "conectado, renovar en " + Math.max(0, facebook.venceEn() / 86400000L) + " días · " + facebook.cuantasFotos() + " fotos"
                            : "sin conectar");
                    if (facebook.error != null) sb.append("\n⚠ ").append(facebook.error);
                }
                else sb.append("\n📱 Teléfono: la tablet no está conectada al WiFi");
                for (String a : agenda.avisos) sb.append("\n⚠ ").append(a);
                if (ultimaFalla != null) sb.append("\n⚠ No se pudo abrir: ").append(ultimaFalla);
                if (!agenda.clima.isEmpty())
                    sb.append("\nClima (").append(agenda.clima).append("): ")
                      .append(clima.texto != null ? clima.texto : clima.error != null ? "⚠ " + clima.error : "buscando…");
                p.info = sb.toString();
            }
        } catch (Throwable t) {
            p.error = "Error: " + t;
            p.segundos = 15;
            System.gc();
        }
        return p;
    }

    private void aplicar(Paso p) {
        if (isFinishing()) return;
        if ("vertical".equals(p.orientacion)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else if ("auto".equals(p.orientacion)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        else if (p.orientacion != null) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float brillo = p.noche ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != brillo) { lp.screenBrightness = brillo; getWindow().setAttributes(lp); }
        nocheV.setVisibility(p.noche ? View.VISIBLE : View.GONE);

        if (p.error != null) {
            avisoV.setText(p.error);
            avisoV.setVisibility(View.VISIBLE);
        } else {
            avisoV.setVisibility(View.GONE);
        }

        if (p.foto != null) {
            final ImageView nueva = fotoV[1 - frente], vieja = fotoV[frente];
            nueva.setImageBitmap(p.foto);
            nueva.animate().alpha(1f).setDuration(FADE_MS).start();
            vieja.animate().alpha(0f).setDuration(FADE_MS).start();
            frente = 1 - frente;
            marcoV.setImageBitmap(p.marco);

            ultimoPaso = p;
            acomodarTextos(p);
        }

        String avisoFb = facebook.avisoPantalla();
        if (avisoFb != null && !p.noche) { fbV.setText(avisoFb); fbV.setVisibility(View.VISIBLE); }
        else fbV.setVisibility(View.GONE);

        if (p.info != null) {
            infoV.setText(p.info);
            infoV.setVisibility(View.VISIBLE);
            ui.removeCallbacks(ocultarInfo);
            ui.postDelayed(ocultarInfo, 8000);
        }
        if (activa) ui.postDelayed(tick, p.segundos * 1000L);
    }

    // ------------------------------------------------------------------ fecha, hora y mensaje

    private Paso ultimoPaso;
    private final Runnable reloj = new Runnable() {
        public void run() {
            if (ultimoPaso != null) acomodarTextos(ultimoPaso); // hora nueva (y fecha a medianoche)
            ui.postDelayed(this, 15000);
        }
    };

    /** Fecha arriba, hora abajo y el mensaje (cumpleaños, feriado) encima de la hora, todo dentro del hueco. */
    private void acomodarTextos(Paso p) {
        int corto = Math.min(p.alto, p.ancho);
        int sep = (int) (corto * 0.025f);
        int anchoHueco = p.ventana.width();
        ponerFechaHora(p);

        float tamFecha = corto * 0.085f, tamHora = corto * 0.062f, tamTexto = corto * 0.07f;
        // Fecha y hora siempre en una línea: si no caben (p. ej. tablet vertical), la letra se achica.
        tamFecha = caber(fechaV, tamFecha, (int) Math.min(anchoHueco * 0.92f, p.textoAnchoArriba * 0.97f));
        tamHora = caber(horaV, tamHora, (int) Math.min(anchoHueco * 0.92f, p.textoAncho * 0.97f));

        int arriba = p.textoDesde + sep, abajo = p.alto - p.textoHasta + sep;
        margenes(fechaV, arriba, 0);
        margenes(horaV, 0, abajo);
        int altoFecha = p.verFecha ? (int) (tamFecha * 1.45f) + dp(12) + sep / 2 : 0;
        if (p.pie != null && !p.noche) {
            pieV.setText(p.pie);
            float tamPie = caber(pieV, corto * 0.042f, (int) Math.min(anchoHueco * 0.9f, p.textoAnchoArriba * 0.95f));
            margenes(pieV, arriba + altoFecha, 0);
            pieV.setVisibility(View.VISIBLE);
            altoFecha += (int) (tamPie * 1.5f) + dp(10) + sep / 2;
        } else pieV.setVisibility(View.GONE);
        int altoHora = p.verHora ? (int) (tamHora * 1.45f) + dp(12) + sep / 2 : 0;

        if (p.texto != null && !p.texto.isEmpty()) {
            textoV.setText(p.texto);
            textoV.setTextSize(TypedValue.COMPLEX_UNIT_PX, tamTexto);
            // si hay adornos a los lados, el mensaje se angosta y pasa a dos líneas
            textoV.setMaxWidth((int) Math.min(anchoHueco * 0.92f, (p.textoArriba ? p.textoAnchoArriba : p.textoAncho) * 0.97f));
            FrameLayout.LayoutParams lt = (FrameLayout.LayoutParams) textoV.getLayoutParams();
            lt.gravity = (p.textoArriba ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
            lt.topMargin = arriba + altoFecha;
            lt.bottomMargin = abajo + altoHora;
            textoV.setLayoutParams(lt);
            textoV.setVisibility(View.VISIBLE);
        } else {
            textoV.setVisibility(View.GONE);
        }
    }

    private void ponerFechaHora(Paso p) {
        Calendar c = Calendar.getInstance();
        if (p.verFecha && !p.noche) {
            String f = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(c.getTime());
            fechaV.setText(Character.toUpperCase(f.charAt(0)) + f.substring(1));
            fechaV.setVisibility(View.VISIBLE);
        } else fechaV.setVisibility(View.GONE);
        if (p.verHora && !p.noche) {
            int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
            String hora = p.hora24 ? String.format(Locale.ROOT, "%d:%02d", h, m)
                    : String.format(Locale.ROOT, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "a. m." : "p. m.");
            String tiempo = clima.texto;
            horaV.setText(tiempo == null ? hora : hora + "   " + tiempo);
            horaV.setVisibility(View.VISIBLE);
        } else horaV.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ pie de foto y recuerdos

    /** "Paseo a la playa · De Ana · 3 de marzo de 2025", o "Un día como hoy · hace 3 años". */
    private String pieDeFoto(File foto, Calendar hoy) {
        Properties d = datosFoto(foto);
        long fecha = fechaDe(foto);
        List<String> partes = new ArrayList<String>();
        String texto = d.getProperty("texto", "").replaceAll("\\s+", " ").trim();
        if (texto.length() > 70) texto = texto.substring(0, 67).trim() + "…";
        if (!texto.isEmpty()) partes.add(texto);
        if (d.getProperty("de") != null) partes.add("De " + d.getProperty("de"));
        int anios = aniosAtras(fecha, hoy);
        if (anios > 0) partes.add("Un día como hoy · hace " + anios + (anios == 1 ? " año" : " años"));
        else if (fecha > 0) partes.add(new SimpleDateFormat("d 'de' MMMM 'de' yyyy", new Locale("es")).format(new java.util.Date(fecha)));
        if (partes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String x : partes) { if (sb.length() > 0) sb.append("  ·  "); sb.append(x); }
        return sb.toString();
    }

    /** Si la foto es de este mismo día y mes de un año anterior, cuántos años hace; si no, 0. */
    private static int aniosAtras(long fecha, Calendar hoy) {
        if (fecha <= 0) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(fecha);
        if (c.get(Calendar.DAY_OF_MONTH) != hoy.get(Calendar.DAY_OF_MONTH) || c.get(Calendar.MONTH) != hoy.get(Calendar.MONTH)) return 0;
        return Math.max(0, hoy.get(Calendar.YEAR) - c.get(Calendar.YEAR));
    }

    /** Datos guardados al bajar la foto de Facebook (fb-123.txt junto a fb-123.jpg). */
    private static Properties datosFoto(File foto) {
        Properties d = new Properties();
        String n = foto.getName();
        int punto = n.lastIndexOf('.');
        File txt = new File(foto.getParentFile(), (punto > 0 ? n.substring(0, punto) : n) + ".txt");
        if (txt.isFile()) {
            try {
                java.io.Reader r = new java.io.InputStreamReader(new FileInputStream(txt), "UTF-8");
                try { d.load(r); } finally { r.close(); }
            } catch (Exception ignored) { }
        }
        return d;
    }

    /** Fecha de la foto: la de Facebook, o la que guardó la cámara (EXIF). 0 si no se sabe. */
    private long fechaDe(File foto) {
        String clave = foto.getAbsolutePath() + "|" + foto.lastModified();
        Long guardada = fechasFotos.get(clave);
        if (guardada != null) return guardada;
        long fecha = 0;
        try { fecha = Long.parseLong(datosFoto(foto).getProperty("fecha", "0")); } catch (NumberFormatException ignored) { }
        if (fecha == 0) {
            try {
                String exif = new ExifInterface(foto.getAbsolutePath()).getAttribute(ExifInterface.TAG_DATETIME);
                if (exif != null && exif.length() >= 19)
                    fecha = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(exif).getTime();
            } catch (Exception ignored) { }
        }
        fechasFotos.put(clave, fecha);
        return fecha;
    }

    /** Pone el tamaño de letra más grande (hasta "base") con el que el texto cabe en una línea. */
    private static float caber(TextView v, float base, int anchoMax) {
        android.graphics.Paint medidor = new android.graphics.Paint(v.getPaint());
        String texto = v.getText().toString();
        int relleno = v.getPaddingLeft() + v.getPaddingRight();
        float tam = base;
        medidor.setTextSize(tam);
        while (tam > base * 0.55f && medidor.measureText(texto) + relleno > anchoMax) {
            tam *= 0.93f;
            medidor.setTextSize(tam);
        }
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, tam);
        v.setMaxWidth(anchoMax);
        v.setSingleLine(tam > base * 0.56f);
        return tam;
    }

    private static void margenes(View v, int arriba, int abajo) {
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) v.getLayoutParams();
        l.topMargin = arriba;
        l.bottomMargin = abajo;
        v.setLayoutParams(l);
    }

    private TextView etiqueta(Typeface letra) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTypeface(letra);
        t.setShadowLayer(8, 0, 2, Color.BLACK);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(16), dp(4), dp(16), dp(6));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0x55000000);
        fondo.setCornerRadius(dp(22));
        t.setBackgroundDrawable(fondo);
        t.setVisibility(View.GONE);
        return t;
    }

    private Typeface fuente(String asset, Typeface siFalla) {
        try { return Typeface.createFromAsset(getAssets(), asset); } catch (Exception e) { return siFalla; }
    }

    /** Al girar la tablet: rearmar todo para la nueva posición (marco, foto y textos). */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration nueva) {
        super.onConfigurationChanged(nueva);
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 350); // esperar a que la pantalla tenga el tamaño nuevo
    }

    // ------------------------------------------------------------------ archivos

    private void recargarSiHaceFalta() {
        if (carpetas.cambio) { carpetas.cambio = false; ultimoEscaneo = 0; }
        File conf = carpetas.fechasActual();
        long mod = conf == null ? -1 : conf.lastModified() * 31 + conf.getAbsolutePath().hashCode();
        if (mod != configMod) {
            configMod = mod;
            agenda = conf == null ? new Agenda() : Agenda.leer(leerLineas(conf));
            servidor.pin = agenda.pin;
            clima.ciudad(agenda.clima);
            ultimoEscaneo = 0;
        }
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoEscaneo < REESCANEO_MS && !fotos.isEmpty()) return;
        ultimoEscaneo = ahora;

        List<File> todas = carpetas.fotos();
        if (agenda.aleatorio) Collections.shuffle(todas);
        else Collections.sort(todas);
        // Las recién subidas (últimos 15 minutos) se muestran primero.
        // Orden: recién subidas (15 min) → "un día como hoy" de otros años → el resto.
        fotos.clear();
        for (File f : todas) if (ahora - f.lastModified() < 15 * 60 * 1000L) fotos.add(f);
        if (agenda.recuerdos) {
            Calendar hoy = Calendar.getInstance();
            for (File f : todas) if (!fotos.contains(f) && aniosAtras(fechaDe(f), hoy) > 0) fotos.add(f);
        }
        for (File f : todas) if (!fotos.contains(f)) fotos.add(f);
        idxFoto = 0;

        marcosArch.clear();
        marcosArch.putAll(carpetas.marcos());
        marcos.clear();
        marcos.addAll(marcosArch.keySet());
    }

    /** Busca la carpeta Portarretratos, primero en la tarjeta SD y luego en la memoria interna. */
    private File buscarCarpeta() {
        Set<String> raices = new LinkedHashSet<String>();
        String sec = System.getenv("SECONDARY_STORAGE");
        if (sec != null) raices.addAll(Arrays.asList(sec.split(":")));
        raices.addAll(Arrays.asList("/storage/sdcard1", "/storage/extSdCard", "/storage/external_SD",
                "/storage/ext_sd", "/mnt/extSdCard", "/mnt/sdcard2", "/mnt/external_sd", "/mnt/extsd",
                "/mnt/sdcard/external_sd", "/storage/usbdisk"));
        if (Build.VERSION.SDK_INT >= 19) {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null) for (File d : dirs) {
                if (d == null) continue;
                String ruta = d.getAbsolutePath();
                int i = ruta.indexOf("/Android/");
                if (i > 0) raices.add(ruta.substring(0, i));
            }
        }
        File[] storage = new File("/storage").listFiles();
        if (storage != null) for (File s : storage) raices.add(s.getAbsolutePath());
        raices.add(Environment.getExternalStorageDirectory().getAbsolutePath());
        raices.add("/storage/sdcard0");
        raices.add("/mnt/sdcard");

        for (String r : raices) {
            File[] hijos = new File(r.trim()).listFiles();
            if (hijos == null) continue;
            for (File h : hijos) if (h.isDirectory() && h.getName().equalsIgnoreCase(CARPETA)) return h;
        }
        return null;
    }

    /** Lee como UTF-8; si el archivo se guardó en otra codificación (Bloc de notas viejo), usa Windows-1252. */
    static List<String> leerLineas(File f) {
        try {
            InputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] bytes = out.toByteArray();
            String texto;
            try {
                texto = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                texto = new String(bytes, "windows-1252");
            }
            return Arrays.asList(texto.split("\r\n|\r|\n"));
        } catch (Exception e) {
            return new ArrayList<String>();
        }
    }

    /** Decodifica reducida al tamaño de la pantalla (la tablet tiene poca memoria) y endereza según EXIF. */
    static Bitmap decodificar(File f, int ancho, int alto, boolean transparente) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int lado = Math.max(ancho, alto), corto = Math.min(ancho, alto);
        int mayor = Math.max(o.outWidth, o.outHeight), menor = Math.min(o.outWidth, o.outHeight);
        int s = 1;
        while (mayor / (s * 2) >= lado && menor / (s * 2) >= corto) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        d.inPreferredConfig = transparente ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        } catch (OutOfMemoryError e) {
            System.gc();
            d.inSampleSize = s * 2;
            bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
        }
        if (bmp == null || transparente) return bmp;
        try {
            int ori = new ExifInterface(f.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int grados = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (grados != 0) {
                Matrix m = new Matrix();
                m.postRotate(grados);
                Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rot != bmp) bmp.recycle();
                bmp = rot;
            }
        } catch (Exception ignored) { }
        return bmp;
    }

    // ------------------------------------------------------------------ utilidades

    private TextView mensaje(int sp) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setVisibility(View.GONE);
        return t;
    }

    private static FrameLayout.LayoutParams lleno() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
