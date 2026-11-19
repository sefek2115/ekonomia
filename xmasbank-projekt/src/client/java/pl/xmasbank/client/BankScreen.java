package pl.xmasbank.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import pl.xmasbank.HistoryEntry;
import pl.xmasbank.Net;

/** Okno banku (ciemnozielone, świąteczne): przelew + historia. Rysowane ręcznie, skaluje się do małych okien. */
public class BankScreen extends Screen {
	private static final int W = 330;
	private static final int H = 168;

	// kolory
	private static final int PANEL = 0xFF1E4A2B;
	private static final int FIELD = 0xFF0F2A18;
	private static final int LABEL = 0xFFB8CCB8;
	private static final int RED = 0xFFC62E3A;
	private static final int GREEN = 0xFF2E9B3A;
	private static final int SOFT = 0xFF4F7F52;
	private static final int GREY = 0xFF6E6E6E;
	private static final int[] BULBS = {0xFFE53935, 0xFF43A047, 0xFFFFC107, 0xFF1E88E5};
	private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");

	// układ (współrzędne lokalne okna)
	private static final int TAB_X = 12, TAB_Y = 29, TAB_W = 60, TAB_H = 14;
	private static final int HIST_X = 218, HIST_Y = 148, HIST_W = 100, HIST_H = 14;
	private static final int SEND_X = 12, SEND_Y = 133, SEND_W = 100, SEND_H = 15;
	private static final int LIST_X = 218, LIST_Y = 59, LIST_W = 92, ROW_H = 15, LIST_ROWS = 5;
	private static final int[] QUICK_X = {136, 160, 184};
	private static final int QUICK_Y = 86, QUICK_W = 22, QUICK_H = 13;

	private static final class Field {
		final int x, y, w, h, max;
		final boolean numeric;
		String text = "";

		Field(int x, int y, int w, int h, int max, boolean numeric) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.max = max;
			this.numeric = numeric;
		}
	}

	private int tab = 0;
	private final Field fTarget = new Field(12, 59, 194, 13, 16, false);
	private final Field fAmount = new Field(12, 86, 120, 13, 9, true);
	private final Field fTitle = new Field(12, 113, 194, 13, 40, false);
	private Field focus = fTarget;
	private int scroll = 0;
	private int onlineScroll = 0;
	private int seenSeq = BankClient.statusSeq;

	private float s = 1f;
	private int ox, oy;
	private List<PlayerInfo> online = new ArrayList<>();
	private long nextOnlineRefresh = 0;

	public BankScreen() {
		super(Component.literal("Bank"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------- rysowanie ----------

	private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		g.fill(x, y, x + w, y + h, c);
	}

	private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		rect(g, x + 1, y, w - 2, h, 0xFF08150C);
		rect(g, x, y + 1, w, h - 2, 0xFF08150C);
		rect(g, x + 1, y + 1, w - 2, h - 2, 0xFF8FB08F);
		rect(g, x + 3, y + 3, w - 6, h - 6, 0xFF0B2514);
		rect(g, x + 4, y + 4, w - 8, h - 8, PANEL);
		// delikatne "ziarno" tła
		for (int i = 0; i < 70; i++) {
			int px = 6 + (i * 53) % (w - 12);
			int py = 6 + (i * 97) % (h - 12);
			rect(g, px, py, 1, 1, 0x16FFFFFF);
		}
	}

	private static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, boolean lit) {
		rect(g, x, y, w, h, lit ? 0xFFE0F0E0 : 0xFF7A957A);
		rect(g, x, y, w - 1, h - 1, 0xFF06140B);
		rect(g, x + 1, y + 1, w - 2, h - 2, fill);
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int color, boolean hover) {
		int c = hover ? lighten(color) : color;
		rect(g, x, y, w, h, 0xFF06140B);
		rect(g, x + 1, y + 1, w - 2, h - 2, c);
		rect(g, x + 1, y + 1, w - 2, 1, lighten(c));
		rect(g, x + 1, y + h - 2, w - 2, 1, darken(c));
		g.text(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, 0xFFFFFFFF, true);
	}

	private static int lighten(int c) {
		int r = Math.min(255, ((c >> 16) & 0xFF) + 36), gr = Math.min(255, ((c >> 8) & 0xFF) + 36), b = Math.min(255, (c & 0xFF) + 36);
		return 0xFF000000 | (r << 16) | (gr << 8) | b;
	}

	private static int darken(int c) {
		return 0xFF000000 | (((c >> 17) & 0x7F) << 16) | (((c >> 9) & 0x7F) << 8) | ((c >> 1) & 0x7F);
	}

	private void label(GuiGraphicsExtractor g, String t, int x, int y) {
		g.text(this.font, t, x, y, LABEL, false);
	}

	private void field(GuiGraphicsExtractor g, Field f, long now) {
		inset(g, f.x, f.y, f.w, f.h, FIELD, focus == f);
		String shown = this.font.plainSubstrByWidth(f.text, f.w - 8, true);
		g.text(this.font, shown, f.x + 3, f.y + 3, 0xFFFFFFFF, false);
		if (focus == f && (now / 500) % 2 == 0) {
			rect(g, f.x + 3 + this.font.width(shown), f.y + 2, 1, f.h - 4, 0xFFFFFFFF);
		}
	}

	private void stripe(GuiGraphicsExtractor g, int x, int y, int n) {
		rect(g, x - 2, y - 2, 5, 5, ((n / 2) & 1) == 0 ? 0xFFD32F2F : 0xFFFFFFFF);
	}

	/** Duża laska cukrowa; dir=+1 lewa (haczyk na zewnątrz w lewo), dir=-1 prawa. */
	private void candy(GuiGraphicsExtractor g, int shaftX, int top, int bottom, int dir) {
		final int r = 9;
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
		for (int i = 1; i <= 3; i++, n++) {
			stripe(g, cx - dir * r, cy + i * 3, n);
		}
	}

	private void garland(GuiGraphicsExtractor g, long now) {
		for (int x = 4; x < W - 4; x++) {
			int y = 5 + (int) (3 * Math.abs(Math.sin((x - 4) * Math.PI / 30.0)));
			rect(g, x, y, 1, 1, 0xFF0E3A1A);
		}
		for (int k = 0; k < 22; k++) {
			int bx = 6 + 15 * k;
			if (bx > W - 6) {
				break;
			}
			int y = 5 + (int) (3 * Math.abs(Math.sin((bx - 4) * Math.PI / 30.0)));
			int c = BULBS[k % 4];
			boolean on = ((now / 450 + k) % 3) != 0;
			if (on) {
				rect(g, bx - 2, y, 5, 7, (0x38 << 24) | (c & 0xFFFFFF));
			} else {
				c = darken(c);
			}
			rect(g, bx - 1, y + 1, 3, 4, c);
			if (on) {
				rect(g, bx, y + 1, 1, 1, 0xFFFFFFFF);
			}
		}
	}

	private void snowCap(GuiGraphicsExtractor g) {
		rect(g, -2, -1, W + 4, 3, 0xFFFFFFFF);
		for (int x = 0; x < W - 6; x += 9) {
			int h = 2 + (x * 7 % 3);
			rect(g, x, 1, 7, h, 0xFFFFFFFF);
			rect(g, x, 1 + h, 7, 1, 0xFFC9DDF0);
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		long now = System.currentTimeMillis();
		BankClient.prune(now);

		g.fill(0, 0, this.width, this.height, 0xB0000000);
		falling(g, now);

		s = Math.min(1f, Math.min((this.width - 56f) / W, (this.height - 12f) / H));
		if (s < 0.3f) {
			s = 0.3f;
		}
		ox = (int) ((this.width - W * s) / 2f);
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

		panel(g, 0, 0, W, H);
		candy(g, -4, 14, 105, 1);
		candy(g, W + 4, 14, 105, -1);
		snowCap(g);
		garland(g, now);

		g.text(this.font, "Świąteczny Bank", 12, 15, 0xFFE8F3E8, true);

		button(g, TAB_X, TAB_Y, TAB_W, TAB_H, "Przelew", tab == 0 ? RED : SOFT, in(lx, ly, TAB_X, TAB_Y, TAB_W, TAB_H));

		if (tab == 0) {
			drawTransfer(g, lx, ly, now);
		} else {
			drawHistory(g);
		}

		// saldo (prawy dół) + przycisk Historia
		String bal = Long.toString(BankClient.balance);
		int total = 10 + 4 + this.font.width(bal);
		int bx = LIST_X + (HIST_W - total) / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, BankHud.COIN, bx, 135, 0, 0, 10, 10, 32, 32, 32, 32);
		g.text(this.font, bal, bx + 14, 136, 0xFFFFFFFF, true);
		button(g, HIST_X, HIST_Y, HIST_W, HIST_H, "Historia", tab == 1 ? RED : SOFT, in(lx, ly, HIST_X, HIST_Y, HIST_W, HIST_H));

		g.pose().popMatrix();
	}

	private void drawTransfer(GuiGraphicsExtractor g, int lx, int ly, long now) {
		label(g, "odbiorca:", 12, 49);
		field(g, fTarget, now);
		label(g, "kwota:", 12, 76);
		field(g, fAmount, now);
		String[] q = {"10", "50", "max"};
		for (int i = 0; i < 3; i++) {
			button(g, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H, q[i], GREY, in(lx, ly, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H));
		}
		label(g, "temat:", 12, 103);
		field(g, fTitle, now);

		label(g, "online (kliknij):", LIST_X, 49);
		int maxOnline = Math.max(0, online.size() - LIST_ROWS);
		onlineScroll = Math.max(0, Math.min(onlineScroll, maxOnline));
		for (int i = 0; i < LIST_ROWS; i++) {
			int y = LIST_Y + i * ROW_H;
			inset(g, LIST_X, y, LIST_W, ROW_H - 1, 0xFF2E5E36, false);
			int idx = i + onlineScroll;
			if (idx < online.size()) {
				PlayerInfo info = online.get(idx);
				PlayerFaceExtractor.extractRenderState(g, info.getSkin(), LIST_X + 2, y + 2, 10);
				String n = this.font.plainSubstrByWidth(info.getProfile().name(), LIST_W - 18);
				boolean hov = in(lx, ly, LIST_X, y, LIST_W, ROW_H - 1);
				g.text(this.font, n, LIST_X + 15, y + 3, hov ? 0xFFFFFF55 : 0xFFFFFFFF, false);
			}
		}
		// pasek przewijania listy online
		int trackH = LIST_ROWS * ROW_H - 1;
		rect(g, 313, LIST_Y, 5, trackH, 0xFF0B1F12);
		if (maxOnline > 0) {
			int barH = Math.max(8, trackH * LIST_ROWS / online.size());
			int barY = LIST_Y + (trackH - barH) * onlineScroll / maxOnline;
			rect(g, 313, barY, 5, barH, 0xFF8FB08F);
		}

		button(g, SEND_X, SEND_Y, SEND_W, SEND_H, "Wyślij", GREEN, in(lx, ly, SEND_X, SEND_Y, SEND_W, SEND_H));
		if (!BankClient.statusText.isEmpty()) {
			String st = this.font.plainSubstrByWidth(BankClient.statusText, 194);
			g.text(this.font, st, 12, 152, BankClient.statusOk ? 0xFF8CE69A : 0xFFFF7B7B, false);
		}
	}

	private void drawHistory(GuiGraphicsExtractor g) {
		label(g, "data", 16, 49);
		label(g, "kwota", 76, 49);
		label(g, "opis", 122, 49);
		List<HistoryEntry> list = BankClient.history;
		int rows = 6;
		int maxScroll = Math.max(0, list.size() - rows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		inset(g, 12, 59, W - 24, rows * 12 + 4, FIELD, false);
		if (list.isEmpty()) {
			g.text(this.font, "Brak transakcji.", 18, 65, 0xFFB8CCB8, false);
		}
		for (int i = 0; i < rows && i + scroll < list.size(); i++) {
			HistoryEntry e = list.get(i + scroll);
			int y = 63 + i * 12;
			g.text(this.font, DATE.format(new Date(e.time())), 16, y, 0xFFB8CCB8, false);
			String amt = (e.amount() > 0 ? "+" : "") + e.amount();
			g.text(this.font, amt, 76, y, e.amount() > 0 ? 0xFF55FF55 : 0xFFFF5555, false);
			g.text(this.font, this.font.plainSubstrByWidth(describe(e), W - 12 - 122 - 10), 122, y, 0xFFFFFFFF, false);
		}
		if (maxScroll > 0) {
			int trackH = rows * 12;
			int barH = Math.max(8, trackH * rows / list.size());
			int barY = 61 + (trackH - barH) * scroll / maxScroll;
			rect(g, W - 18, barY, 3, barH, 0xFF8FB08F);
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
		List<PlayerInfo> list = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null && mc.player != null) {
			String me = mc.player.getName().getString();
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				if (!info.getProfile().name().equals(me)) {
					list.add(info);
				}
			}
		}
		list.sort(Comparator.comparing(i -> i.getProfile().name().toLowerCase()));
		online = list;
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

		if (in(lx, ly, TAB_X, TAB_Y, TAB_W, TAB_H)) {
			tab = 0;
			return true;
		}
		if (in(lx, ly, HIST_X, HIST_Y, HIST_W, HIST_H)) {
			tab = 1;
			return true;
		}
		if (tab == 0) {
			for (Field f : new Field[] {fTarget, fAmount, fTitle}) {
				if (in(lx, ly, f.x, f.y, f.w, f.h)) {
					focus = f;
					return true;
				}
			}
			String[] q = {"10", "50", "max"};
			for (int i = 0; i < 3; i++) {
				if (in(lx, ly, QUICK_X[i], QUICK_Y, QUICK_W, QUICK_H)) {
					fAmount.text = q[i].equals("max") ? Long.toString(BankClient.balance) : q[i];
					return true;
				}
			}
			for (int i = 0; i < LIST_ROWS; i++) {
				int idx = i + onlineScroll;
				if (idx < online.size() && in(lx, ly, LIST_X, LIST_Y + i * ROW_H, LIST_W, ROW_H - 1)) {
					fTarget.text = online.get(idx).getProfile().name();
					focus = fAmount;
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
		if (tab == 1) {
			scroll += dir * 2;
			return true;
		}
		if (tab == 0 && in(lx, ly, LIST_X, LIST_Y, LIST_W + 8, LIST_ROWS * ROW_H)) {
			onlineScroll += dir;
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
		long amount;
		try {
			amount = fAmount.text.isEmpty() ? 0 : Long.parseLong(fAmount.text);
		} catch (NumberFormatException e) {
			amount = 0;
		}
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
