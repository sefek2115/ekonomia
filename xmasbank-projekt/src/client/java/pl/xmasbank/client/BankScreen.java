package pl.xmasbank.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import pl.xmasbank.HistoryEntry;
import pl.xmasbank.Net;

/** Okno banku w stylu "szkła": dwa zaokrąglone panele (przelew/historia + lista online), świąteczne ozdoby. */
public class BankScreen extends Screen {
	// układ całości
	private static final int MAIN_W = 276;
	private static final int ON_X = 284, ON_W = 112;
	private static final int TW = ON_X + ON_W;   // szerokość całości
	private static final int H = 156;

	// kolory
	private static final int PANEL = 0xD0132822;
	private static final int BORDER = 0x55FFFFFF;
	private static final int SOFT = 0x30FFFFFF;
	private static final int SOFT_BORDER = 0x45FFFFFF;
	private static final int RED = 0xFFC62E3A;
	private static final int GREEN = 0xFF22A04A;
	private static final int LABEL = 0xFFD0DDD6;
	private static final int HINT = 0xFF8A9E96;
	private static final int[] BULBS = {0xFFE53935, 0xFF43A047, 0xFFFFC107, 0xFF1E88E5};
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");

	// elementy (współrzędne lokalne)
	private static final int TAB1_X = 10, TAB2_X = 66, TAB_Y = 16, TAB_W = 52, TAB_H = 14;
	private static final int SEND_X = 10, SEND_Y = 118, SEND_W = 64, SEND_H = 16;
	private static final int[] QUICK_X = {104, 126, 148};
	private static final int QUICK_Y = 71, QUICK_W = 20, QUICK_H = 13;
	private static final int LIST_X = ON_X + 8, LIST_Y = 30, LIST_W = 92, ROW_H = 17, LIST_ROWS = 7;

	private static final class Field {
		final int x, y, w, h, max;
		final boolean numeric;
		final String hint;
		String text = "";

		Field(int x, int y, int w, int h, int max, boolean numeric, String hint) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.max = max;
			this.numeric = numeric;
			this.hint = hint;
		}
	}

	private int tab = 0;
	private final Field fTarget = new Field(10, 45, 160, 13, 16, false, "Wpisz nick odbiorcy");
	private final Field fAmount = new Field(10, 71, 90, 13, 9, true, "0");
	private final Field fTitle = new Field(10, 97, 160, 13, 40, false, "Opcjonalny tytuł przelewu");
	private Field focus = fTarget;
	private int scroll = 0;
	private int onlineScroll = 0;
	private int seenSeq = BankClient.statusSeq;

	private float s = 1f;
	private int ox, oy;
	private List<String> online = new ArrayList<>();
	private long nextOnlineRefresh = 0;

	public BankScreen() {
		super(Component.literal("Bank"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------- prymitywy rysowania ----------

	private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		if (w > 0 && h > 0) {
			g.fill(x, y, x + w, y + h, c);
		}
	}

	private static int rinset(int r, int i) {
		double d = r - i - 0.5;
		return (int) Math.round(r - Math.sqrt(r * r - d * d));
	}

	/** Wypełniony zaokrąglony prostokąt (bez nakładania pikseli, więc działa z przezroczystością). */
	private static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
		r = Math.min(r, Math.min(w, h) / 2);
		for (int i = 0; i < r; i++) {
			int in = rinset(r, i);
			rect(g, x + in, y + i, w - 2 * in, 1, c);
			rect(g, x + in, y + h - 1 - i, w - 2 * in, 1, c);
		}
		rect(g, x, y + r, w, h - 2 * r, c);
	}

	/** Ramka 1 px zaokrąglonego prostokąta. */
	private static void rborder(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
		r = Math.min(r, Math.min(w, h) / 2);
		int in0 = rinset(r, 0);
		rect(g, x + in0, y, w - 2 * in0, 1, c);
		rect(g, x + in0, y + h - 1, w - 2 * in0, 1, c);
		for (int i = 1; i < r; i++) {
			int in = rinset(r, i);
			rect(g, x + in, y + i, 1, 1, c);
			rect(g, x + w - 1 - in, y + i, 1, 1, c);
			rect(g, x + in, y + h - 1 - i, 1, 1, c);
			rect(g, x + w - 1 - in, y + h - 1 - i, 1, 1, c);
		}
		rect(g, x, y + r, 1, h - 2 * r, c);
		rect(g, x + w - 1, y + r, 1, h - 2 * r, c);
	}

	private static void glass(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		rrect(g, x, y, w, h, 6, PANEL);
		rrect(g, x + 1, y + 1, w - 2, 24, 5, 0x0FFFFFFF);
		rborder(g, x, y, w, h, 6, BORDER);
	}

	private static int lighten(int c) {
		int a = c >>> 24;
		int r = Math.min(255, ((c >> 16) & 0xFF) + 30), gr = Math.min(255, ((c >> 8) & 0xFF) + 30), b = Math.min(255, (c & 0xFF) + 30);
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}

	private void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int fill, int glow, boolean hover) {
		if (glow != 0) {
			rrect(g, x - 2, y - 2, w + 4, h + 4, h / 2 + 2, glow);
		}
		rrect(g, x, y, w, h, h / 2, hover ? lighten(fill) : fill);
		if ((fill >>> 24) < 0xF0) {
			rborder(g, x, y, w, h, h / 2, SOFT_BORDER);
		}
		g.text(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, 0xFFFFFFFF, true);
	}

	private void smallBtn(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean hover) {
		rrect(g, x, y, w, h, 4, hover ? 0x50FFFFFF : SOFT);
		rborder(g, x, y, w, h, 4, SOFT_BORDER);
		g.text(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, 0xFFFFFFFF, true);
	}

	private void label(GuiGraphicsExtractor g, String t, int x, int y) {
		g.text(this.font, t, x, y, LABEL, false);
	}

	private void field(GuiGraphicsExtractor g, Field f, long now) {
		boolean on = focus == f;
		rrect(g, f.x, f.y, f.w, f.h, 4, on ? 0x50FFFFFF : SOFT);
		rborder(g, f.x, f.y, f.w, f.h, 4, on ? 0xB0FFFFFF : SOFT_BORDER);
		boolean caret = on && (now / 500) % 2 == 0;
		if (f.text.isEmpty()) {
			if (!on) {
				g.text(this.font, f.hint, f.x + 5, f.y + 3, HINT, false);
			}
			if (caret) {
				rect(g, f.x + 5, f.y + 2, 1, f.h - 4, 0xFFFFFFFF);
			}
			return;
		}
		String shown = this.font.plainSubstrByWidth(f.text, f.w - 10, true);
		g.text(this.font, shown, f.x + 5, f.y + 3, 0xFFFFFFFF, false);
		if (caret) {
			rect(g, f.x + 5 + this.font.width(shown), f.y + 2, 1, f.h - 4, 0xFFFFFFFF);
		}
	}

	private void stripe(GuiGraphicsExtractor g, int x, int y, int n) {
		rect(g, x - 2, y - 2, 4, 4, ((n / 2) & 1) == 0 ? 0xFFD32F2F : 0xFFFFFFFF);
	}

	/** Laska cukrowa: trzon w shaftX od top do bottom, haczyk zakręca w prawo (dir=-1) lub w lewo (dir=+1). */
	private void candy(GuiGraphicsExtractor g, int shaftX, int top, int bottom, int dir) {
		final int r = 6;
		int cx = shaftX - dir * r;
		int cy = top + r;
		int n = 0;
		for (int y = bottom; y >= cy; y -= 3, n++) {
			stripe(g, shaftX, y, n);
		}
		int steps = (int) Math.ceil(Math.PI * r / 3.0);
		for (int i = 1; i <= steps; i++, n++) {
			double a = Math.PI * i / steps;
			int px = (int) Math.round(cx + dir * r * Math.cos(a));
			int py = (int) Math.round(cy - r * Math.sin(a));
			stripe(g, px, py, n);
		}
		for (int i = 1; i <= 2; i++, n++) {
			stripe(g, cx - dir * r, cy + i * 3, n);
		}
	}

	private void garland(GuiGraphicsExtractor g, int x0, int w, long now, int seed) {
		for (int x = 8; x < w - 8; x++) {
			int y = 3 + (int) (2.5 * Math.abs(Math.sin((x - 8) * Math.PI / 28.0)));
			rect(g, x0 + x, y, 1, 1, 0xFF4A6B58);
		}
		for (int k = 0; ; k++) {
			int bx = 14 + 14 * k;
			if (bx > w - 12) {
				break;
			}
			int y = 3 + (int) (2.5 * Math.abs(Math.sin((bx - 8) * Math.PI / 28.0)));
			int c = BULBS[(k + seed) % 4];
			boolean on = ((now / 450 + k + seed) % 3) != 0;
			if (on) {
				rect(g, x0 + bx - 2, y, 5, 7, (0x40 << 24) | (c & 0xFFFFFF));
			} else {
				c = (0xFF000000) | (((c >> 1) & 0x7F7F7F));
			}
			rect(g, x0 + bx - 1, y + 1, 3, 4, c);
			if (on) {
				rect(g, x0 + bx, y + 1, 1, 1, 0xFFFFFFFF);
			}
		}
	}

	private void falling(GuiGraphicsExtractor g, long now) {
		for (int i = 0; i < 30; i++) {
			int seed = i * 7919 + 13;
			float speed = 12f + (seed % 17);
			int px = (int) (((seed * 37L) % Math.max(1, this.width)) + 4 * Math.sin(now / 900.0 + i));
			int py = (int) (((now / 1000.0) * speed + (seed % 997)) % Math.max(1, this.height));
			int size = (i % 3 == 0) ? 2 : 1;
			rect(g, px, py, size, size, 0xB0FFFFFF);
		}
	}

	// ---------- render ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.currentTimeMillis();
		BankClient.prune(now);

		g.fill(0, 0, this.width, this.height, 0xB0060F0C);
		falling(g, now);

		s = Math.min(1f, Math.min((this.width - 40f) / TW, (this.height - 44f) / H));
		if (s < 0.3f) {
			s = 0.3f;
		}
		ox = (int) ((this.width - TW * s) / 2f);
		oy = (int) ((this.height - H * s) / 2f);
		int lx = (int) ((mouseX - ox) / s);
		int ly = (int) ((mouseY - oy) / s);

		if (BankClient.statusSeq != seenSeq) {
			seenSeq = BankClient.statusSeq;
			if (BankClient.statusOk) {
				fAmount.text = "";
				fTitle.text = "";
			}
		}
		if (now > nextOnlineRefresh) {
			refreshOnline();
			nextOnlineRefresh = now + 1000;
		}

		g.pose().pushMatrix();
		g.pose().translate((float) ox, (float) oy);
		g.pose().scale(s, s);

		glass(g, 0, 0, MAIN_W, H);
		glass(g, ON_X, 0, ON_W, H);
		garland(g, 0, MAIN_W, now, 0);
		garland(g, ON_X, ON_W, now, 2);
		candy(g, 7, -16, 14, -1);
		candy(g, MAIN_W - 8, -16, 14, -1);
		candy(g, TW - 8, -16, 14, -1);

		// zakładki
		boolean h1 = in(lx, ly, TAB1_X, TAB_Y, TAB_W, TAB_H);
		boolean h2 = in(lx, ly, TAB2_X, TAB_Y, TAB_W, TAB_H);
		if (tab == 0) {
			pill(g, TAB1_X, TAB_Y, TAB_W, TAB_H, "Przelew", RED, 0x30FF4050, h1);
			pill(g, TAB2_X, TAB_Y, TAB_W, TAB_H, "Historia", SOFT, 0, h2);
		} else {
			pill(g, TAB1_X, TAB_Y, TAB_W, TAB_H, "Przelew", SOFT, 0, h1);
			pill(g, TAB2_X, TAB_Y, TAB_W, TAB_H, "Historia", RED, 0x30FF4050, h2);
		}

		// saldo
		String bal = Long.toString(BankClient.balance);
		int bw = Math.max(44, 24 + this.font.width(bal));
		int bx = MAIN_W - 10 - bw;
		rrect(g, bx, TAB_Y, bw, TAB_H, 7, SOFT);
		rborder(g, bx, TAB_Y, bw, TAB_H, 7, SOFT_BORDER);
		g.blit(RenderPipelines.GUI_TEXTURED, BankHud.COIN, bx + 5, TAB_Y + 2, 0, 0, 10, 10, 32, 32, 32, 32);
		g.text(this.font, bal, bx + 19, TAB_Y + 3, 0xFFFFFFFF, true);

		if (tab == 0) {
			drawTransfer(g, lx, ly, now);
		} else {
			drawHistory(g);
		}
		drawOnline(g, lx, ly);

		g.pose().popMatrix();
	}

	private long parseAmount() {
		try {
			return fAmount.text.isEmpty() ? 0 : Long.parseLong(fAmount.text);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void kv(GuiGraphicsExtractor g, int x, int y, String k, String v, int color) {
		g.text(this.font, k, x, y, HINT, false);
		g.text(this.font, this.font.plainSubstrByWidth(v, 72), x, y + 10, color, false);
	}

	private void drawTransfer(GuiGraphicsExtractor g, int lx, int ly, long now) {
		label(g, "ODBIORCA", 10, 36);
		field(g, fTarget, now);
		label(g, "KWOTA", 10, 62);
		field(g, fAmount, now);
		String[] q = {"10", "50", "MAX"};
		for (int i = 0; i < 3; i++) {
			smallBtn(g, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H, q[i], in(lx, ly, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H));
		}
		label(g, "TEMAT", 10, 88);
		field(g, fTitle, now);

		pill(g, SEND_X, SEND_Y, SEND_W, SEND_H, "Wyślij", GREEN, 0x2822C55E, in(lx, ly, SEND_X, SEND_Y, SEND_W, SEND_H));
		if (!BankClient.statusText.isEmpty()) {
			String st = this.font.plainSubstrByWidth(BankClient.statusText, 160);
			g.text(this.font, st, 10, 140, BankClient.statusOk ? 0xFF7CE08F : 0xFFFF8A8A, false);
		}

		// podgląd przelewu
		label(g, "PODGLĄD", 182, 36);
		rrect(g, 182, 47, 84, 98, 5, 0x22FFFFFF);
		rborder(g, 182, 47, 84, 98, 5, SOFT_BORDER);
		long amt = parseAmount();
		String name = fTarget.text.trim();
		String title = fTitle.text.trim();
		long after = BankClient.balance - amt;
		kv(g, 188, 52, "Do:", name.isEmpty() ? "-" : name, 0xFFFFFFFF);
		kv(g, 188, 74, "Kwota:", amt > 0 ? Long.toString(amt) : "-", 0xFFFFFFFF);
		kv(g, 188, 96, "Temat:", title.isEmpty() ? "-" : title, 0xFFFFFFFF);
		kv(g, 188, 118, "Saldo po:", Long.toString(after), after < 0 ? 0xFFFF8A8A : 0xFF7CE08F);
	}

	private void drawHistory(GuiGraphicsExtractor g) {
		label(g, "DATA", 16, 36);
		label(g, "KWOTA", 76, 36);
		label(g, "OPIS", 122, 36);
		List<HistoryEntry> list = BankClient.history;
		int rows = 8;
		int maxScroll = Math.max(0, list.size() - rows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		rrect(g, 10, 46, MAIN_W - 20, rows * 13 + 6, 5, 0x22FFFFFF);
		rborder(g, 10, 46, MAIN_W - 20, rows * 13 + 6, 5, SOFT_BORDER);
		if (list.isEmpty()) {
			g.text(this.font, "Brak transakcji.", 16, 53, HINT, false);
		}
		for (int i = 0; i < rows && i + scroll < list.size(); i++) {
			HistoryEntry e = list.get(i + scroll);
			int y = 51 + i * 13;
			g.text(this.font, DATE.format(new Date(e.time())), 16, y, HINT, false);
			String amt = (e.amount() > 0 ? "+" : "") + e.amount();
			g.text(this.font, amt, 76, y, e.amount() > 0 ? 0xFF55FF77 : 0xFFFF6B6B, false);
			g.text(this.font, this.font.plainSubstrByWidth(describe(e), MAIN_W - 122 - 22), 122, y, 0xFFFFFFFF, false);
		}
		if (maxScroll > 0) {
			int trackH = rows * 13;
			int barH = Math.max(8, trackH * rows / list.size());
			int barY = 49 + (trackH - barH) * scroll / maxScroll;
			rrect(g, MAIN_W - 17, barY, 3, barH, 1, 0x90FFFFFF);
		}
	}

	private void drawOnline(GuiGraphicsExtractor g, int lx, int ly) {
		g.text(this.font, "ONLINE (KLIKNIJ)", ON_X + 8, 16, LABEL, false);
		int maxOnline = Math.max(0, online.size() - LIST_ROWS);
		onlineScroll = Math.max(0, Math.min(onlineScroll, maxOnline));
		for (int i = 0; i < LIST_ROWS; i++) {
			int idx = i + onlineScroll;
			if (idx >= online.size()) {
				break;
			}
			int y = LIST_Y + i * ROW_H;
			boolean hov = in(lx, ly, LIST_X, y, LIST_W, ROW_H - 2);
			rrect(g, LIST_X, y, LIST_W, ROW_H - 2, 5, hov ? 0x48FFFFFF : SOFT);
			rborder(g, LIST_X, y, LIST_W, ROW_H - 2, 5, SOFT_BORDER);
			// zielona kropka
			rect(g, LIST_X + 7, y + 5, 2, 4, 0xFF3DDC84);
			rect(g, LIST_X + 6, y + 6, 4, 2, 0xFF3DDC84);
			g.text(this.font, this.font.plainSubstrByWidth(online.get(idx), LIST_W - 22), LIST_X + 14, y + 4, 0xFFFFFFFF, true);
		}
		if (online.isEmpty()) {
			g.text(this.font, "Nikogo poza Tobą.", LIST_X, LIST_Y + 4, HINT, false);
		}
		if (maxOnline > 0) {
			int trackH = LIST_ROWS * ROW_H - 2;
			rrect(g, ON_X + ON_W - 8, LIST_Y, 3, trackH, 1, 0x30FFFFFF);
			int barH = Math.max(8, trackH * LIST_ROWS / online.size());
			int barY = LIST_Y + (trackH - barH) * onlineScroll / maxOnline;
			rrect(g, ON_X + ON_W - 8, barY, 3, barH, 1, 0x90FFFFFF);
		}
	}

	private static String describe(HistoryEntry e) {
		return switch (e.kind()) {
			case "in" -> "Od " + e.other() + (e.title().isEmpty() ? "" : ": " + e.title());
			case "out" -> "Do " + e.other() + (e.title().isEmpty() ? "" : ": " + e.title());
			default -> e.title();
		};
	}

	private void refreshOnline() {
		List<String> names = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null && mc.player != null) {
			String me = mc.player.getName().getString();
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				String n = info.getProfile().name();
				if (!n.equals(me)) {
					names.add(n);
				}
			}
		}
		names.sort(Comparator.comparing(String::toLowerCase));
		online = names;
	}

	// ---------- sterowanie ----------

	private static boolean in(int px, int py, int x, int y, int w, int h) {
		return px >= x && px < x + w && py >= y && py < y + h;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		int lx = (int) ((event.x() - ox) / s);
		int ly = (int) ((event.y() - oy) / s);

		if (in(lx, ly, TAB1_X, TAB_Y, TAB_W, TAB_H)) {
			tab = 0;
			return true;
		}
		if (in(lx, ly, TAB2_X, TAB_Y, TAB_W, TAB_H)) {
			tab = 1;
			return true;
		}
		for (int i = 0; i < LIST_ROWS; i++) {
			int idx = i + onlineScroll;
			if (idx < online.size() && in(lx, ly, LIST_X, LIST_Y + i * ROW_H, LIST_W, ROW_H - 2)) {
				fTarget.text = online.get(idx);
				focus = fAmount;
				tab = 0;
				return true;
			}
		}
		if (tab == 0) {
			for (Field f : new Field[] {fTarget, fAmount, fTitle}) {
				if (in(lx, ly, f.x, f.y, f.w, f.h)) {
					focus = f;
					return true;
				}
			}
			String[] q = {"10", "50", "MAX"};
			for (int i = 0; i < 3; i++) {
				if (in(lx, ly, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H)) {
					fAmount.text = q[i].equals("MAX") ? Long.toString(BankClient.balance) : q[i];
					return true;
				}
			}
			if (in(lx, ly, SEND_X, SEND_Y, SEND_W, SEND_H)) {
				send();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int lx = (int) ((mouseX - ox) / s);
		int ly = (int) ((mouseY - oy) / s);
		int dir = -(int) Math.signum(scrollY);
		if (lx >= ON_X) {
			onlineScroll += dir;
			return true;
		}
		if (tab == 1) {
			scroll += dir * 2;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (tab != 0 || focus == null) {
			return super.charTyped(event);
		}
		int cp = event.codepoint();
		if (cp < 32 || cp == 127) {
			return false;
		}
		if (focus.numeric && (cp < '0' || cp > '9')) {
			return true;
		}
		if (focus.text.length() < focus.max) {
			focus.text += new String(Character.toChars(cp));
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (tab == 0 && focus != null) {
			if (key == 259) { // Backspace
				if (!focus.text.isEmpty()) {
					focus.text = focus.text.substring(0, focus.text.length() - 1);
				}
				return true;
			}
			if (key == 258) { // Tab
				focus = focus == fTarget ? fAmount : focus == fAmount ? fTitle : fTarget;
				return true;
			}
			if (key == 257 || key == 335) { // Enter
				send();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private void send() {
		String target = fTarget.text.trim();
		long amount = parseAmount();
		if (target.isEmpty()) {
			setStatus("Wpisz nick odbiorcy.", false);
			return;
		}
		if (amount <= 0) {
			setStatus("Wpisz kwotę większą od 0.", false);
			return;
		}
		if (amount > BankClient.balance) {
			setStatus("Za mało środków na koncie.", false);
			return;
		}
		ClientPlayNetworking.send(new Net.TransferPayload(target, amount, fTitle.text.trim()));
		setStatus("Wysyłanie...", true);
	}

	private void setStatus(String text, boolean ok) {
		BankClient.statusText = text;
		BankClient.statusOk = ok;
	}
}
