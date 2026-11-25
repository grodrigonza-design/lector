package cr.portarretratos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee fechas.txt y decide qué marco y qué texto tocan en un día dado.
 * Java puro (sin Android) para poder probarlo fuera de la tablet.
 *
 * Prioridad: cumpleaños > fecha especial > temporada > mes > marco normal.
 * Si para una regla no existe ningún archivo de marco, se pasa a la siguiente,
 * así la app funciona aunque todavía falten marcos por hacer.
 */
public class Agenda {

    public static final String SEMANA_SANTA = "SEMANA_SANTA";
    public static final String DIA_DEL_PADRE = "DIA_DEL_PADRE";

    static class Cumple { int dia, mes; String nombre; String marco; }
    static class Fecha { int dia, mes; String especial; String marco; String texto; }
    static class Temporada { int desde, hasta; String marco; String texto; } // mes*100+dia

    public static class Resultado {
        public final List<String> archivos = new ArrayList<String>();
        public String texto;
        public String motivo = "sin marco";
        /** true = no hay versión vertical del marco: hay que girar el horizontal 90°. */
        public boolean girar;
    }

    // Ajustes
    public int segundos = 20;
    public boolean aleatorio = true;
    public int nocheInicio = -1, nocheFin = -1; // minutos desde medianoche
    public String orientacion = "auto";      // auto (gira con la tablet) | horizontal | vertical
    public boolean mostrarFecha = true, mostrarHora = true, hora24 = false;
    public boolean mostrarPie = true, recuerdos = true;
    public String clima = "";               // ciudad para el clima ("" = no mostrar)
    public int avisoCumple = 7;             // avisar cumpleaños próximos con N días (0 = no)
    public boolean textoArriba = false;
    public String pin = null;               // PIN para entrar desde el teléfono
    public String ajuste = "inteligente"; // inteligente | recortar | completa
    public String textoCumple = "¡Feliz cumpleaños, {nombre}!";
    public String marcoCumple = "cumple";
    public String marcoNormal = "normal";

    final List<Cumple> cumples = new ArrayList<Cumple>();
    final List<Fecha> fechas = new ArrayList<Fecha>();
    final List<Temporada> temporadas = new ArrayList<Temporada>();
    final String[] meses = new String[13];
    public final List<String> avisos = new ArrayList<String>();

    private static final Pattern DM = Pattern.compile("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})");
    private static final String[] NOMBRES_MES = {"", "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};

    public static Agenda leer(List<String> lineas) {
        Agenda a = new Agenda();
        int n = 0;
        for (String cruda : lineas) {
            n++;
            String linea = cruda.replace("﻿", "").trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;
            int igual = linea.indexOf('=');
            if (igual < 0) { a.avisos.add("Línea " + n + ": falta el signo ="); continue; }
            String izq = linea.substring(0, igual).trim();
            String der = linea.substring(igual + 1).trim();
            String clave = normalizar(izq);
            String[] partes = der.split("\\|", 2);
            String valor = partes[0].trim();
            String extra = partes.length > 1 ? partes[1].trim() : null;
            if (extra != null && extra.isEmpty()) extra = null;
            try {
                if (!a.procesar(clave, izq, der, valor, extra)) a.avisos.add("Línea " + n + ": no la entendí (" + linea + ")");
            } catch (RuntimeException e) {
                a.avisos.add("Línea " + n + ": " + e.getMessage());
            }
        }
        return a;
    }

    private boolean procesar(String clave, String izq, String der, String valor, String extra) {
        if (clave.startsWith("cumple ")) {
            int[] dm = diaMes(clave.substring(7));
            if (dm == null) return true; // plantilla sin llenar (??/??): se ignora sin aviso
            if (valor.isEmpty()) throw new IllegalArgumentException("falta el nombre");
            Cumple c = new Cumple(); c.dia = dm[0]; c.mes = dm[1]; c.nombre = valor; c.marco = extra;
            cumples.add(c);
            return true;
        }
        if (clave.startsWith("fecha ")) {
            String arg = clave.substring(6).trim();
            Fecha f = new Fecha(); f.marco = valor; f.texto = extra;
            String esp = arg.toUpperCase(Locale.ROOT).replace(' ', '_');
            if (esp.equals(SEMANA_SANTA) || esp.equals(DIA_DEL_PADRE)) f.especial = esp;
            else {
                int[] dm = diaMes(arg);
                if (dm == null) throw new IllegalArgumentException("fecha inválida '" + arg + "'");
                f.dia = dm[0]; f.mes = dm[1];
            }
            fechas.add(f);
            return true;
        }
        if (clave.startsWith("temporada ")) {
            Matcher m = DM.matcher(clave);
            int[] d = new int[4]; int i = 0;
            while (i < 4 && m.find()) { d[i++] = Integer.parseInt(m.group(1)); d[i++] = Integer.parseInt(m.group(2)); }
            if (i < 4 || !valida(d[0], d[1]) || !valida(d[2], d[3]))
                throw new IllegalArgumentException("temporada inválida, use DD/MM-DD/MM");
            Temporada t = new Temporada(); t.desde = d[1] * 100 + d[0]; t.hasta = d[3] * 100 + d[2];
            t.marco = valor; t.texto = extra;
            temporadas.add(t);
            return true;
        }
        if (clave.startsWith("mes ")) {
            String arg = clave.substring(4).trim();
            int mes = -1;
            for (int i = 1; i <= 12; i++) if (NOMBRES_MES[i].equals(arg)) mes = i;
            if (mes < 0) try { mes = Integer.parseInt(arg); } catch (NumberFormatException ignored) { }
            if (mes < 1 || mes > 12) throw new IllegalArgumentException("mes inválido '" + arg + "'");
            meses[mes] = valor;
            return true;
        }
        if (clave.equals("segundos")) { segundos = Math.max(3, Integer.parseInt(valor)); return true; }
        if (clave.equals("orden")) { aleatorio = !normalizar(valor).startsWith("nombre"); return true; }
        if (clave.equals("orientacion")) { orientacion = normalizar(valor); return true; }
        if (clave.equals("mostrar fecha")) { mostrarFecha = esSi(valor); return true; }
        if (clave.equals("mostrar hora")) { mostrarHora = esSi(valor); return true; }
        if (clave.equals("mostrar pie")) { mostrarPie = esSi(valor); return true; }
        if (clave.equals("recuerdos")) { recuerdos = esSi(valor); return true; }
        if (clave.equals("clima")) { clima = esSi(valor) || valor.trim().isEmpty() ? valor.trim() : ""; return true; }
        if (clave.equals("aviso cumple")) { try { avisoCumple = Math.max(0, Integer.parseInt(valor.replaceAll("[^0-9]", ""))); } catch (NumberFormatException e) { avisoCumple = 0; } return true; }
        if (clave.equals("formato hora")) { hora24 = valor.trim().startsWith("24"); return true; }
        if (clave.equals("pin")) { pin = valor.isEmpty() ? null : valor; return true; }
        if (clave.equals("ajuste")) { ajuste = normalizar(valor); return true; }
        if (clave.equals("posicion texto")) { textoArriba = normalizar(valor).startsWith("arriba"); return true; }
        if (clave.equals("texto cumple")) { textoCumple = der; return true; }
        if (clave.equals("marco cumple")) { marcoCumple = valor; return true; }
        if (clave.equals("normal") || clave.equals("marco normal")) { marcoNormal = valor; return true; }
        if (clave.equals("noche")) {
            String v = normalizar(valor);
            if (v.isEmpty() || v.equals("no")) { nocheInicio = nocheFin = -1; return true; }
            Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").matcher(v);
            if (!m.find()) throw new IllegalArgumentException("horario de noche inválido, use 22:00-06:00");
            nocheInicio = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            nocheFin = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
            return true;
        }
        return false;
    }

    public boolean esNoche(Calendar c) {
        if (nocheInicio < 0 || nocheInicio == nocheFin) return false;
        int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (nocheInicio < nocheFin) return min >= nocheInicio && min < nocheFin;
        return min >= nocheInicio || min < nocheFin;
    }

    /** @param disponibles nombres de archivo de la carpeta marcos (con extensión) */
    public Resultado decidir(Calendar hoy, List<String> disponibles) { return decidir(hoy, disponibles, false); }

    /** @param vertical la tablet está parada: se prefiere "marco-vertical.png" (o se gira el horizontal). */
    public Resultado decidir(Calendar hoy, List<String> disponibles, boolean vertical) {
        Resultado r = new Resultado();
        int dia = hoy.get(Calendar.DAY_OF_MONTH), mes = hoy.get(Calendar.MONTH) + 1;
        int clave = mes * 100 + dia;

        // 1. Cumpleaños
        List<Cumple> hoyCumplen = new ArrayList<Cumple>();
        for (Cumple c : cumples) if (c.dia == dia && c.mes == mes) hoyCumplen.add(c);
        if (!hoyCumplen.isEmpty()) {
            List<String> nombres = new ArrayList<String>();
            for (Cumple c : hoyCumplen) nombres.add(c.nombre);
            r.texto = textoCumple.replace("{nombre}", unir(nombres));
            for (Cumple c : hoyCumplen)
                if (c.marco != null && usar(r, c.marco, disponibles, "cumpleaños de " + c.nombre, vertical)) return r;
            if (usar(r, marcoCumple, disponibles, "cumpleaños de " + unir(nombres), vertical)) return r;
        }
        // 2. Fechas especiales
        for (Fecha f : fechas) {
            if (!coincide(f, hoy)) continue;
            if (r.texto == null && f.texto != null) r.texto = f.texto;
            if (usar(r, f.marco, disponibles, "fecha especial (" + f.marco + ")", vertical)) return r;
        }
        // 3. Temporadas
        for (Temporada t : temporadas) {
            boolean dentro = t.desde <= t.hasta ? (clave >= t.desde && clave <= t.hasta)
                    : (clave >= t.desde || clave <= t.hasta);
            if (!dentro) continue;
            if (r.texto == null && t.texto != null) r.texto = t.texto;
            if (usar(r, t.marco, disponibles, "temporada (" + t.marco + ")", vertical)) return r;
        }
        // 4. Mes
        if (meses[mes] != null && usar(r, meses[mes], disponibles, "marco del mes (" + meses[mes] + ")", vertical)) return r;
        // 5. Normal
        usar(r, marcoNormal, disponibles, "marco normal", vertical);
        return r;
    }

    /** "Mañana: cumpleaños de Ana" / "En 3 días: cumpleaños de Ana y Luis", o null. No cuenta el de hoy. */
    public String proximoCumple(Calendar hoy) {
        if (avisoCumple <= 0) return null;
        Calendar c = (Calendar) hoy.clone();
        for (int d = 1; d <= avisoCumple; d++) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            List<String> nombres = new ArrayList<String>();
            for (Cumple k : cumples)
                if (k.dia == c.get(Calendar.DAY_OF_MONTH) && k.mes == c.get(Calendar.MONTH) + 1) nombres.add(k.nombre);
            if (!nombres.isEmpty())
                return (d == 1 ? "Mañana" : "En " + d + " días") + ": cumpleaños de " + unir(nombres) + " 🎂";
        }
        return null;
    }

    private boolean coincide(Fecha f, Calendar hoy) {
        int dia = hoy.get(Calendar.DAY_OF_MONTH), mes = hoy.get(Calendar.MONTH) + 1, anio = hoy.get(Calendar.YEAR);
        if (f.especial == null) return f.dia == dia && f.mes == mes;
        if (f.especial.equals(DIA_DEL_PADRE)) {
            // tercer domingo de junio
            return mes == 6 && hoy.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && dia >= 15 && dia <= 21;
        }
        // Semana Santa: del Domingo de Ramos al Domingo de Resurrección
        int doy = hoy.get(Calendar.DAY_OF_YEAR);
        Calendar pascua = pascua(anio);
        int p = pascua.get(Calendar.DAY_OF_YEAR);
        return doy >= p - 7 && doy <= p;
    }

    /** Domingo de Pascua (algoritmo gregoriano anónimo). */
    public static Calendar pascua(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451, mes = (h + l - 7 * m + 114) / 31, dia = ((h + l - 7 * m + 114) % 31) + 1;
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(y, mes - 1, dia);
        return cal;
    }

    private static boolean usar(Resultado r, String marco, List<String> disponibles, String motivo, boolean vertical) {
        List<String> encontrados = vertical ? buscarMarcos(marco + "-vertical", disponibles) : new ArrayList<String>();
        r.girar = vertical && encontrados.isEmpty();
        if (encontrados.isEmpty()) encontrados = buscarMarcos(marco, disponibles);
        if (encontrados.isEmpty()) return false;
        r.archivos.addAll(encontrados);
        r.motivo = motivo;
        return true;
    }

    /** "navidad" encuentra navidad.png, navidad2.png, navidad-3.jpg... (no "navidad-ninos.png"). */
    public static List<String> buscarMarcos(String marco, List<String> disponibles) {
        List<String> res = new ArrayList<String>();
        Pattern p = Pattern.compile(Pattern.quote(normalizar(marco)) + "([ _\\-]?\\d+)?");
        for (String archivo : disponibles) {
            int punto = archivo.lastIndexOf('.');
            String base = normalizar(punto > 0 ? archivo.substring(0, punto) : archivo);
            if (p.matcher(base).matches()) res.add(archivo);
        }
        java.util.Collections.sort(res);
        return res;
    }

    static String unir(List<String> nombres) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nombres.size(); i++) {
            if (i > 0) sb.append(i == nombres.size() - 1 ? " y " : ", ");
            sb.append(nombres.get(i));
        }
        return sb.toString();
    }

    private static int[] diaMes(String s) {
        Matcher m = DM.matcher(s);
        if (!m.find()) return null;
        int d = Integer.parseInt(m.group(1)), mes = Integer.parseInt(m.group(2));
        if (!valida(d, mes)) throw new IllegalArgumentException("fecha inválida '" + s.trim() + "'");
        return new int[]{d, mes};
    }

    private static boolean esSi(String v) {
        String n = normalizar(v);
        return !(n.startsWith("no") || n.equals("0") || n.startsWith("ocult"));
    }

    private static boolean valida(int d, int m) { return m >= 1 && m <= 12 && d >= 1 && d <= 31; }

    /** minúsculas, sin tildes, espacios simples */
    public static String normalizar(String s) {
        String n = Normalizer.normalize(s.replace("ñ", "ni").replace("Ñ", "NI"), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
