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
    private TextView textoV, infoV, avisoV;
    private View nocheV;

    // Estado (solo se toca desde el hilo de fondo)
    private File base;
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
        Bitmap foto, marco; String texto, info, error; boolean noche, textoArriba; int segundos = 20;
        Rect ventana; int ancho, alto, textoDesde, textoHasta, textoAncho;
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

        avisoV = mensaje(16);
        avisoV.setGravity(Gravity.CENTER);
        raiz.addView(avisoV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        infoV = mensaje(12);
        infoV.setBackgroundColor(0xCC000000);
        raiz.addView(infoV, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        nocheV = new View(this);
        nocheV.setBackgroundColor(Color.BLACK);
        nocheV.setVisibility(View.GONE);
        raiz.addView(nocheV, lleno());

        // Tocar: siguiente foto + información. Tocar de noche: enciende 1 minuto.
        raiz.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { mostrarInfo = true; siguiente(); }
        });
        setContentView(raiz);
        pantallaCompleta();
    }

    private volatile boolean mostrarInfo = false;

    @Override protected void onResume() { super.onResume(); activa = true; siguiente(); }
    @Override protected void onPause() { super.onPause(); activa = false; ui.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); fondo.shutdownNow(); }

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
        p.ancho = ancho; p.alto = alto; p.ventana = new Rect(0, 0, ancho, alto); p.textoHasta = alto; p.textoAncho = ancho;
        try {
            if (base == null || !base.isDirectory()) { base = buscarCarpeta(); configMod = -2; }
            if (base == null) {
                p.error = "No encuentro la carpeta \"" + CARPETA + "\".\n\n"
                        + "Ponga en la tarjeta SD una carpeta llamada " + CARPETA + "\n"
                        + "con las subcarpetas  fotos  y  marcos.\n\nReintentando cada 10 segundos…";
                p.segundos = 10;
                return p;
            }
            recargarSiHaceFalta();
            p.segundos = agenda.segundos;
            p.orientacion = agenda.orientacion;
            p.textoArriba = agenda.textoArriba;

            Calendar ahora = Calendar.getInstance();
            if (agenda.esNoche(ahora) && System.currentTimeMillis() > despiertoHasta) {
                p.noche = true;
                p.segundos = 60;
                return p;
            }
            if (fotos.isEmpty()) {
                p.error = "No hay fotos en\n" + new File(base, "fotos").getAbsolutePath()
                        + "\n\nCopie ahí sus fotos (.jpg o .png).";
                p.segundos = 30;
                return p;
            }

            Agenda.Resultado r = agenda.decidir(ahora, marcos);
            p.texto = r.texto;
            if (!r.archivos.isEmpty()) {
                String nombre = r.archivos.get(idxMarco++ % r.archivos.size());
                String clave = nombre + "@" + ancho + "x" + alto;
                if (!clave.equals(marcoCargado)) {
                    marcoBmp = null;
                    Bitmap crudo = decodificar(new File(new File(base, "marcos"), nombre), ancho, alto, true);
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
            p.textoAncho = marcoBmp != null ? marcoTexto[agenda.textoArriba ? 3 : 2] : ancho;

            if (idxFoto >= fotos.size()) { idxFoto = 0; ultimoEscaneo = 0; recargarSiHaceFalta(); }
            File foto = fotos.get(idxFoto++ % fotos.size());
            Bitmap original = decodificar(foto, ancho, alto, false);
            if (original != null) {
                p.foto = Fotos.componer(original, p.ventana, ancho, alto, agenda.ajuste);
                original.recycle();
            }

            if (info) {
                SimpleDateFormat f = new SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", new Locale("es"));
                StringBuilder sb = new StringBuilder();
                sb.append(f.format(ahora.getTime())).append('\n')
                  .append("Marco: ").append(r.motivo).append(marcoCargado != null ? " → " + marcoCargado : "").append('\n')
                  .append("Foto ").append(idxFoto).append(" de ").append(fotos.size()).append(": ").append(foto.getName()).append('\n')
                  .append("Marcos en la tarjeta: ").append(marcos.size()).append('\n')
                  .append("Carpeta: ").append(base.getAbsolutePath());
                for (String a : agenda.avisos) sb.append("\n⚠ ").append(a);
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
        else if ("auto".equals(p.orientacion)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
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

            if (p.texto != null && !p.texto.isEmpty()) {
                textoV.setText(p.texto);
                int altoPantalla = Math.min(p.alto, p.ancho);
                textoV.setTextSize(TypedValue.COMPLEX_UNIT_PX, altoPantalla * 0.075f);
                // si hay adornos a los lados, el mensaje se angosta y pasa a dos líneas
                textoV.setMaxWidth((int) Math.min(p.ventana.width() * 0.92f, p.textoAncho * 0.97f));
                FrameLayout.LayoutParams lt = (FrameLayout.LayoutParams) textoV.getLayoutParams();
                lt.gravity = (p.textoArriba ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
                // dentro del hueco, sin tapar adornos del marco que entren en él (pastel, moño…)
                int sep = (int) (altoPantalla * 0.03f);
                lt.topMargin = p.textoDesde + sep;
                lt.bottomMargin = p.alto - p.textoHasta + sep;
                textoV.setLayoutParams(lt);
                textoV.setVisibility(View.VISIBLE);
            } else {
                textoV.setVisibility(View.GONE);
            }
        }

        if (p.info != null) {
            infoV.setText(p.info);
            infoV.setVisibility(View.VISIBLE);
            ui.removeCallbacks(ocultarInfo);
            ui.postDelayed(ocultarInfo, 8000);
        }
        if (activa) ui.postDelayed(tick, p.segundos * 1000L);
    }

    // ------------------------------------------------------------------ archivos

    private void recargarSiHaceFalta() {
        File conf = new File(base, "fechas.txt");
        long mod = conf.exists() ? conf.lastModified() : -1;
        if (mod != configMod) {
            configMod = mod;
            agenda = mod < 0 ? new Agenda() : Agenda.leer(leerLineas(conf));
            ultimoEscaneo = 0;
        }
        if (System.currentTimeMillis() - ultimoEscaneo < REESCANEO_MS && !fotos.isEmpty()) return;
        ultimoEscaneo = System.currentTimeMillis();

        fotos.clear();
        juntarFotos(new File(base, "fotos"), fotos, 0);
        if (agenda.aleatorio) Collections.shuffle(fotos);
        else Collections.sort(fotos);
        idxFoto = 0;

        marcos.clear();
        String[] nombres = new File(base, "marcos").list();
        if (nombres != null) for (String n : nombres) if (esImagen(n)) marcos.add(n);
    }

    private static void juntarFotos(File dir, List<File> salida, int nivel) {
        File[] hijos = dir.listFiles();
        if (hijos == null || nivel > 4) return;
        for (File f : hijos) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) juntarFotos(f, salida, nivel + 1);
            else if (esImagen(f.getName())) salida.add(f);
        }
    }

    private static boolean esImagen(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return !l.startsWith(".") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")
                || l.endsWith(".webp") || l.endsWith(".bmp") || l.endsWith(".gif"));
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
    private static List<String> leerLineas(File f) {
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
    private static Bitmap decodificar(File f, int ancho, int alto, boolean transparente) {
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
