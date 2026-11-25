package cr.portarretratos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Temperatura y estado del tiempo para mostrar junto a la hora.
 * Usa Open-Meteo (gratis, sin registro ni llave). La ciudad se escribe en fechas.txt:
 *   clima = San José, Costa Rica
 */
final class Clima {

    static String GEO = "https://geocoding-api.open-meteo.com";
    static String API = "https://api.open-meteo.com";
    private static final long CADA = 30 * 60 * 1000L;

    private volatile String ciudad = "";
    private String ciudadBuscada = "";
    private double lat, lon;
    private long ultima;
    private volatile boolean vivo;

    /** Texto corto para la pantalla, p. ej. "24° ☀", o null si no hay datos. */
    volatile String texto;
    volatile String error;

    void ciudad(String c) {
        c = c == null ? "" : c.trim();
        if (!c.equals(ciudad)) { ciudad = c; ultima = 0; texto = null; }
    }

    void iniciar() {
        vivo = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (vivo) {
                    try { if (!ciudad.isEmpty() && System.currentTimeMillis() - ultima > CADA) actualizar(); }
                    catch (Throwable e) { error = Facebook.describir(e); ultima = System.currentTimeMillis() - CADA + 5 * 60 * 1000L; }
                    try { Thread.sleep(20000); } catch (InterruptedException ignored) { }
                }
            }
        }, "clima");
        t.setDaemon(true);
        t.start();
    }

    void detener() { vivo = false; }

    private void actualizar() throws Exception {
        String c = ciudad;
        if (!c.equals(ciudadBuscada)) {
            // "San José, Costa Rica": se busca el nombre y, si hay país, se prefiere ese país
            String[] partes = c.split(",", 2);
            String nombre = partes[0].trim(), pais = partes.length > 1 ? Agenda.normalizar(partes[1]) : "";
            JSONObject g = new JSONObject(Red.pedir("GET", GEO + "/v1/search?count=10&language=es&name="
                    + URLEncoder.encode(nombre, "UTF-8"), null));
            JSONArray res = g.optJSONArray("results");
            if (res == null || res.length() == 0) { error = "No encuentro la ciudad «" + c + "» para el clima"; texto = null; ultima = System.currentTimeMillis(); return; }
            JSONObject elegido = res.getJSONObject(0);
            if (!pais.isEmpty()) for (int i = 0; i < res.length(); i++) {
                JSONObject o = res.getJSONObject(i);
                if (Agenda.normalizar(o.optString("country")).contains(pais)) { elegido = o; break; }
            }
            lat = elegido.getDouble("latitude");
            lon = elegido.getDouble("longitude");
            ciudadBuscada = c;
        }
        JSONObject w = new JSONObject(Red.pedir("GET", API + "/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&timezone=auto", null));
        JSONObject actual = w.getJSONObject("current");
        long temp = Math.round(actual.getDouble("temperature_2m"));
        texto = temp + "° " + icono(actual.optInt("weather_code"), actual.optInt("is_day", 1) == 1);
        error = null;
        ultima = System.currentTimeMillis();
    }

    /** Códigos WMO de Open-Meteo → un símbolo sencillo. */
    static String icono(int codigo, boolean dia) {
        if (codigo == 0) return dia ? "☀" : "☾";
        if (codigo <= 2) return dia ? "⛅" : "☾";
        if (codigo == 3) return "☁";
        if (codigo == 45 || codigo == 48) return "🌫";
        if (codigo >= 95) return "⛈";
        if (codigo >= 71 && codigo <= 77 || codigo == 85 || codigo == 86) return "❄";
        if (codigo >= 51) return "☂";
        return "";
    }
}
