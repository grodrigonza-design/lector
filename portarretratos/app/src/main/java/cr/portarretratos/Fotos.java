package cr.portarretratos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Arma la imagen final de cada foto, del tamaño exacto de la pantalla. */
final class Fotos {
    private Fotos() { }

    /**
     * Abre el hueco del marco (si viene con centro de color).
     * @param texto recibe {arriba, abajo}: la franja libre de adornos donde puede ir el mensaje
     * @return dónde va la foto
     */
    static Rect prepararMarco(Bitmap marco, int[] texto) {
        int w = marco.getWidth(), h = marco.getHeight();
        int[] px = new int[w * h];
        marco.getPixels(px, 0, w, 0, 0, w, h);
        int[] r = Hueco.abrir(px, w, h);
        if (r == null) { texto[0] = 0; texto[1] = h; return new Rect(0, 0, w, h); }
        marco.setPixels(px, 0, w, 0, 0, w, h);
        texto[0] = r[4]; texto[1] = r[5];
        return new Rect(r[0], r[1], r[2], r[3]);
    }

    static Bitmap componer(Bitmap foto, Rect hueco, int ancho, int alto, String ajuste) {
        Bitmap salida = Bitmap.createBitmap(ancho, alto, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(salida);
        Paint suave = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int fw = foto.getWidth(), fh = foto.getHeight();

        // 1. Fondo: la misma foto muy difuminada y oscurecida, llenando toda la pantalla.
        dibujarDifuminado(c, foto, ancho, alto);

        if (Hueco.recortar(fw, fh, hueco.width(), hueco.height(), ajuste)) {
            // 2a. Llenar el hueco (un poco más grande, para que entre debajo del borde del marco).
            int m = (int) (Math.min(ancho, alto) * 0.02f);
            Rect destino = new Rect(Math.max(0, hueco.left - m), Math.max(0, hueco.top - m),
                    Math.min(ancho, hueco.right + m), Math.min(alto, hueco.bottom + m));
            c.drawBitmap(foto, recorte(fw, fh, destino.width(), destino.height()), destino, suave);
        } else {
            // 2b. Foto completa, centrada en el hueco, con una sombra suave.
            float escala = Math.min(hueco.width() / (float) fw, hueco.height() / (float) fh);
            float dw = fw * escala, dh = fh * escala;
            float x = hueco.left + (hueco.width() - dw) / 2f, y = hueco.top + (hueco.height() - dh) / 2f;
            RectF destino = new RectF(x, y, x + dw, y + dh);
            Paint sombra = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 6; i >= 1; i--) {
                sombra.setColor((14 + 6 * (6 - i)) << 24);
                c.drawRect(destino.left - i * 2, destino.top - i, destino.right + i * 2, destino.bottom + i * 3, sombra);
            }
            c.drawBitmap(foto, null, destino, suave);
        }
        return salida;
    }

    /** Recorte centrado; en vertical se corre hacia arriba, donde suelen estar las caras. */
    static Rect recorte(int fw, int fh, int dw, int dh) {
        float rf = fw / (float) fh, rd = dw / (float) dh;
        if (rf > rd) {
            int w = Math.round(fh * rd), x = (fw - w) / 2;
            return new Rect(x, 0, x + w, fh);
        }
        int h = Math.round(fw / rd), y = Math.round((fh - h) * 0.35f);
        return new Rect(0, y, fw, y + h);
    }

    /** Difuminado barato: reducir muchísimo y volver a ampliar con filtro, en dos pasos. */
    private static void dibujarDifuminado(Canvas c, Bitmap foto, int ancho, int alto) {
        Paint suave = new Paint(Paint.FILTER_BITMAP_FLAG);
        int pw = Math.max(4, ancho / 32), ph = Math.max(4, alto / 32);
        Bitmap chico = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        new Canvas(chico).drawBitmap(foto, recorte(foto.getWidth(), foto.getHeight(), pw, ph), new Rect(0, 0, pw, ph), suave);
        Bitmap medio = Bitmap.createBitmap(pw * 4, ph * 4, Bitmap.Config.ARGB_8888);
        new Canvas(medio).drawBitmap(chico, null, new Rect(0, 0, pw * 4, ph * 4), suave);
        chico.recycle();
        c.drawBitmap(medio, null, new Rect(0, 0, ancho, alto), suave);
        medio.recycle();
        c.drawColor(0x73000000); // oscurecer para que la foto principal resalte
    }
}
