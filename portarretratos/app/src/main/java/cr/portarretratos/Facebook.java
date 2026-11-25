package cr.portarretratos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Trae las fotos que una persona sube a Facebook.
 *
 * Usa "Facebook Login for Devices" (como los televisores): la tablet muestra un código,
 * la persona entra a facebook.com/device en su celular, lo escribe y acepta.
 * El permiso dura ~60 días; una semana antes de vencer, la tablet vuelve a mostrar un
 * código para renovarlo (mientras tanto sigue descargando fotos).
 *
 * Todo se guarda en facebook.txt dentro de la carpeta propia de la app (nunca en GitHub).
 */
final class Facebook {

    static String GRAPH = "https://graph.facebook.com";   // se cambia solo en pruebas
    static final String PERMISOS = "public_profile,user_photos";
    private static final long HORA = 60 * 60 * 1000L, DIA = 24 * HORA;
    private static final long CADA = HORA;                 // cada cuánto buscar fotos nuevas
    private static final long AVISO_RENOVAR = 7 * DIA;     // mostrar el código 7 días antes de vencer
    private static final int MAXIMO_FOTOS = 400;           // se borran las más viejas

    private final Carpetas carpetas;
    private volatile boolean vivo;
    private volatile boolean revisarYa, conectarYa;

    // Estado visible (pantalla y página del teléfono)
    volatile String codigo;          // código para facebook.com/device (null si no hay)
    volatile long codigoVence;
    volatile String error;
    volatile String ultimoMensaje = "";
    private String ultimoFormatoMalo = "";

    // Datos internos del inicio de sesión con código
    private String codigoLargo;
    private int intervalo = 5;
    private long proximoCodigo;

    Facebook(Carpetas carpetas) { this.carpetas = carpetas; }

    // ------------------------------------------------------------------ configuración guardada

    private File archivo() { return new File(carpetas.propiaEscritura(), "facebook.txt"); }
    File carpetaFotos() { File d = new File(carpetas.propiaEscritura(), "fotos-facebook"); d.mkdirs(); return d; }

    synchronized Properties datos() {
        Properties p = new Properties();
        for (File pr : carpetas.propias()) {
            File f = new File(pr, "facebook.txt");
            if (!f.isFile()) continue;
            try {
                InputStreamReader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
                try { p.load(r); } finally { r.close(); }
                break;
            } catch (Exception ignored) { }
        }
        return p;
    }

    synchronized void guardar(Properties p) {
        try {
            File f = archivo(), tmp = new File(f.getAbsolutePath() + ".tmp");
            f.getParentFile().mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try { p.store(w, "Portarretratos - Facebook (no compartir)"); } finally { w.close(); }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception e) { error = "No se pudo guardar la configuración: " + e.getMessage(); }
    }

    void configurar(String appId, String clientToken, String album) {
        Properties p = datos();
        if (appId != null) p.setProperty("app_id", appId.trim());
        if (clientToken != null) p.setProperty("client_token", clientToken.trim());
        if (album != null) p.setProperty("album", album.trim());
        error = null;
        guardar(p); // si falla, deja el error para mostrarlo
        conectarYa = true;
    }

    void desconectar() {
        Properties p = datos();
        p.remove("token"); p.remove("vence"); p.remove("datos_vencen"); p.remove("nombre");
        guardar(p);
        codigo = null; codigoLargo = null;
    }

    void conectarAhora() { conectarYa = true; proximoCodigo = 0; }
    void revisarAhora() { revisarYa = true; }

    boolean configurado() { Properties p = datos(); return !p.getProperty("app_id", "").isEmpty() && !p.getProperty("client_token", "").isEmpty(); }
    boolean conectado() { return !datos().getProperty("token", "").isEmpty() && venceEn() > 0; }

    /** Milisegundos hasta que haya que renovar (el menor de los dos vencimientos de Facebook). */
    long venceEn() {
        Properties p = datos();
        long v = num(p, "vence"), d = num(p, "datos_vencen");
        long fin = v > 0 && d > 0 ? Math.min(v, d) : Math.max(v, d);
        return fin <= 0 ? 0 : fin - System.currentTimeMillis();
    }

    int cuantasFotos() {
        String[] n = carpetaFotos().list();
        return n == null ? 0 : n.length;
    }

    /** Texto para mostrar en la pantalla del portarretratos, o null si no hay nada que avisar. */
    String avisoPantalla() {
        String c = codigo;
        if (c == null) return null;
        String quien = datos().getProperty("nombre", "");
        boolean renovar = conectado();
        return (renovar ? "Para seguir viendo sus fotos de Facebook" : "Para ver sus fotos de Facebook")
                + (quien.isEmpty() ? "" : " (" + quien + ")") + ":\n"
                + "entre a  facebook.com/device  en su celular\ny escriba el código   " + c;
    }

    // ------------------------------------------------------------------ ciclo

    void iniciar() {
        vivo = true;
        Thread t = new Thread(new Runnable() { public void run() { ciclo(); } }, "facebook");
        t.setDaemon(true);
        t.start();
    }

    void detener() { vivo = false; }

    private void ciclo() {
        while (vivo) {
            long espera = 5000;
            try {
                if (!configurado()) { codigo = null; espera = 15000; }
                else {
                    boolean conectado = conectado();
                    boolean renovar = !conectado || venceEn() < AVISO_RENOVAR || conectarYa;
                    if (renovar) espera = Math.max(1000, pasoInicioSesion() * 1000L);
                    else { codigo = null; codigoLargo = null; }
                    Properties p = datos();
                    if (conectado() && (revisarYa || System.currentTimeMillis() - num(p, "ultima_revision") > CADA)) {
                        revisarYa = false;
                        sincronizar();
                    }
                }
            } catch (Throwable e) {
                error = describir(e);
                espera = 60000;
            }
            dormir(espera);
        }
    }

    /** Un paso del inicio de sesión con código. Devuelve cuántos segundos esperar. */
    private int pasoInicioSesion() throws Exception {
        Properties p = datos();
        String clave = p.getProperty("app_id") + "|" + p.getProperty("client_token");
        long ahora = System.currentTimeMillis();
        if (codigoLargo == null || ahora > codigoVence) {
            if (ahora < proximoCodigo) return 5;
            JSONObject r = new JSONObject(Red.pedir("POST", GRAPH + "/device/login",
                    "access_token=" + enc(clave) + "&scope=" + enc(PERMISOS)));
            if (r.has("error")) {
                error = "Facebook no dio un código: " + r.getJSONObject("error").optString("message");
                codigo = null; codigoLargo = null;
                proximoCodigo = ahora + 10 * 60 * 1000L; // no insistir: reintentar en 10 minutos
                return 30;
            }
            codigoLargo = r.getString("code");
            codigo = r.getString("user_code");
            intervalo = Math.max(5, r.optInt("interval", 5));
            codigoVence = ahora + r.optInt("expires_in", 420) * 1000L;
            error = null;
            return intervalo;
        }
        JSONObject r = new JSONObject(Red.pedir("POST", GRAPH + "/device/login_status",
                "access_token=" + enc(clave) + "&code=" + enc(codigoLargo)));
        if (r.has("access_token")) {
            Properties d = datos();
            d.setProperty("token", r.getString("access_token"));
            long dura = r.optLong("expires_in", 60L * 24 * 3600);
            d.setProperty("vence", String.valueOf(ahora + dura * 1000L));
            long datosVencen = r.optLong("data_access_expiration_time", 0);
            if (datosVencen > 0) d.setProperty("datos_vencen", String.valueOf(datosVencen * 1000L));
            else d.remove("datos_vencen");
            guardar(d);
            codigo = null; codigoLargo = null; conectarYa = false; error = null;
            try {
                JSONObject yo = new JSONObject(Red.pedir("GET", GRAPH + "/me?fields=id,name&access_token="
                        + enc(r.getString("access_token")), null));
                d = datos();
                String antes = d.getProperty("cuenta_id", ""), ahoraId = yo.optString("id", "");
                if (!antes.isEmpty() && !ahoraId.isEmpty() && !antes.equals(ahoraId)) {
                    // Se conectó otra persona: quitar las fotos de la cuenta anterior.
                    File[] viejas = carpetaFotos().listFiles();
                    if (viejas != null) for (File v : viejas) v.delete();
                    carpetas.cambio = true;
                }
                if (!ahoraId.isEmpty()) d.setProperty("cuenta_id", ahoraId);
                if (yo.has("name")) d.setProperty("nombre", yo.getString("name"));
                guardar(d);
            } catch (Exception ignored) { }
            revisarYa = true;
            return 1;
        }
        JSONObject e = r.optJSONObject("error");
        int sub = e == null ? 0 : e.optInt("error_subcode", 0);
        if (sub == 1349174) return intervalo;                  // todavía no ha escrito el código
        if (sub == 1349172) { intervalo += 5; return intervalo; } // pide ir más despacio
        if (sub == 1349152) { codigoLargo = null; return 1; }  // el código venció: pedir otro
        error = "Facebook: " + (e == null ? r.toString() : e.optString("message"));
        codigoLargo = null;
        proximoCodigo = ahora + 60 * 1000L;
        return 30;
    }

    /** Descarga las fotos nuevas que la persona subió a Facebook. */
    private void sincronizar() throws Exception {
        Properties p = datos();
        String token = p.getProperty("token");
        String album = Agenda.normalizar(p.getProperty("album", ""));
        String url = GRAPH + "/me/photos?type=uploaded&limit=100&fields=" + enc("id,created_time,images,album{name}")
                + "&access_token=" + enc(token);
        JSONObject r = new JSONObject(Red.pedir("GET", url, null));
        if (r.has("error")) {
            JSONObject e = r.getJSONObject("error");
            if (e.optInt("code") == 190) { // permiso vencido o retirado
                desconectar();
                error = "El permiso de Facebook venció: hay que volver a conectar.";
                return;
            }
            throw new Exception("Facebook: " + e.optString("message"));
        }
        JSONArray lista = r.optJSONArray("data");
        File dir = carpetaFotos();
        int nuevas = 0, malas = 0, total = lista == null ? 0 : lista.length();
        Set<String> vistas = new HashSet<String>();
        for (int i = 0; i < total; i++) {
            JSONObject foto = lista.getJSONObject(i);
            if (!album.isEmpty()) {
                JSONObject al = foto.optJSONObject("album");
                if (al == null || !Agenda.normalizar(al.optString("name")).equals(album)) continue;
            }
            String id = foto.getString("id").replaceAll("[^0-9_]", "");
            File destino = new File(dir, "fb-" + id + ".jpg");
            vistas.add(destino.getName());
            if (destino.isFile()) {
                if (Diagnostico.abrible(Diagnostico.tipo(destino))) continue;
                destino.delete(); // quedó dañada o en un formato que no abre: bajarla otra vez
            }
            String fuente = mejorTamano(foto.optJSONArray("images"));
            if (fuente == null) continue;
            String tipo = Red.descargar(fuente, destino);
            String real = Diagnostico.tipo(destino);
            if (!Diagnostico.abrible(real)) {
                // Formato que Android 4.4 no abre (p. ej. HEIC/AVIF): no guardarlo y avisar.
                destino.delete();
                malas++;
                ultimoFormatoMalo = real + " / " + tipo;
                continue;
            }
            nuevas++;
        }
        limpiarViejas(dir);
        p = datos();
        p.setProperty("ultima_revision", String.valueOf(System.currentTimeMillis()));
        guardar(p);
        error = malas == 0 ? null : malas + " fotos vinieron en un formato que la tablet no abre (" + ultimoFormatoMalo + ")";
        ultimoMensaje = nuevas == 0 ? "Sin fotos nuevas" : nuevas + (nuevas == 1 ? " foto nueva" : " fotos nuevas");
        if (nuevas > 0) carpetas.cambio = true;
    }

    /** La versión más pequeña que todavía se ve bien en la pantalla (≥ 1280 px), o la más grande. */
    static String mejorTamano(JSONArray imagenes) throws Exception {
        if (imagenes == null || imagenes.length() == 0) return null;
        String elegida = null; int lado = Integer.MAX_VALUE;
        String grande = null; int ladoGrande = 0;
        for (int i = 0; i < imagenes.length(); i++) {
            JSONObject im = imagenes.getJSONObject(i);
            int l = Math.max(im.optInt("width"), im.optInt("height"));
            if (l > ladoGrande) { ladoGrande = l; grande = im.optString("source"); }
            if (l >= 1280 && l < lado) { lado = l; elegida = im.optString("source"); }
        }
        return elegida != null ? elegida : grande;
    }

    private static void limpiarViejas(File dir) {
        File[] fotos = dir.listFiles();
        if (fotos == null || fotos.length <= MAXIMO_FOTOS) return;
        Arrays.sort(fotos, new Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (int i = 0; i < fotos.length - MAXIMO_FOTOS; i++) fotos[i].delete();
    }

    // ------------------------------------------------------------------ estado para el teléfono

    String estadoJson() {
        try {
            Properties p = datos();
            JSONObject j = new JSONObject();
            j.put("configurado", configurado());
            j.put("app_id", p.getProperty("app_id", ""));
            j.put("album", p.getProperty("album", ""));
            j.put("conectado", conectado());
            j.put("nombre", p.getProperty("nombre", ""));
            j.put("dias", Math.max(0, venceEn() / DIA));
            j.put("codigo", codigo == null ? JSONObject.NULL : codigo);
            j.put("fotos", cuantasFotos());
            long ult = num(p, "ultima_revision");
            j.put("ultima_revision", ult);
            j.put("mensaje", ultimoMensaje);
            j.put("error", error == null ? JSONObject.NULL : error);
            return j.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static long num(Properties p, String k) {
        try { return Long.parseLong(p.getProperty(k, "0")); } catch (NumberFormatException e) { return 0; }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    static String describir(Throwable e) {
        String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (e instanceof javax.net.ssl.SSLException) return "La tablet no pudo conectarse de forma segura a Facebook (" + m + ")";
        if (e instanceof java.net.UnknownHostException) return "Sin internet (no se encuentra Facebook)";
        return m;
    }

    private static void dormir(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
