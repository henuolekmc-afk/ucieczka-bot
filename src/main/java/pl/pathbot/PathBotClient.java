package pl.pathbot;

import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Nagrywa KAZDY tick gracza: pozycja, kierunek patrzenia oraz wcisniete klawisze
 * (W/S/A/D, skok, sprint, sneak). Odtwarzanie powtarza dokladnie te same inputy,
 * a po dojsciu do konca wraca na start i zaczyna od nowa.
 */
public class PathBotClient implements ClientModInitializer {

	private enum State { IDLE, RECORDING, APPROACH, REPLAY }

	// Ramka: {x, y, z, yaw, pitch, flagi, slot hotbaru}
	private static final int FWD = 1, BACK = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SPRINT = 32, SNEAK = 64, ATTACK = 128, USE = 256;

	private static final double START_REACH = 0.35;   // jak blisko startu zaczynamy odtwarzanie
	private static final double RESYNC_DIST = 1.5;    // odchylka od nagrania, po ktorej szukamy najblizszej ramki
	private static final double LOST_DIST = 4.0;      // dalej niz to = bot zgubil trase
	private static final int STUCK_LIMIT = 100;       // ticki bez ruchu w fazie dojscia na start

	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("pathbot.json");
	private final Gson gson = new Gson();

	private KeyBinding recordKey, playKey, showKey;

	private State state = State.IDLE;
	private List<double[]> frames = new ArrayList<>();
	private int idx = 0;
	private int stuck = 0;
	private Vec3d lastPos = Vec3d.ZERO;
	private int loops = 0;
	private int lastFlags = 0;
	private boolean showPath = true;
	private int particleTick = 0;

	@Override
	public void onInitializeClient() {
		recordKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.pathbot.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.pathbot"));
		playKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.pathbot.play", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.pathbot"));

		showKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.pathbot.show", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.pathbot"));

		// /botucieczka info
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("botucieczka")
				.executes(ctx -> { sendInfo(ctx.getSource()); return 1; })
				.then(ClientCommandManager.literal("info")
					.executes(ctx -> { sendInfo(ctx.getSource()); return 1; }))));

		load();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			state = State.IDLE;
			return;
		}

		while (recordKey.wasPressed()) toggleRecording(client);
		while (playKey.wasPressed()) togglePlaying(client);
		while (showKey.wasPressed()) {
			showPath = !showPath;
			msg(player, showPath ? "Podglad trasy: wlaczony" : "Podglad trasy: wylaczony");
		}

		switch (state) {
			case RECORDING -> record(client, player);
			case APPROACH -> approach(client, player);
			case REPLAY -> replay(client, player);
			default -> { }
		}

		drawPath(client, player);
	}

	private void sendInfo(FabricClientCommandSource src) {
		src.sendFeedback(Text.literal("=== Ucieczka Bot ===").formatted(Formatting.GOLD, Formatting.BOLD));
		src.sendFeedback(Text.literal("Nagrywa Twoją trasę (ruch, skoki, sprint, kliknięcia, slot paska) i odtwarza ją w pętli.")
			.formatted(Formatting.GRAY));
		src.sendFeedback(infoLine(recordKey.getBoundKeyLocalizedText(),
			"start/stop nagrywania. Trasa zapisuje się do config/pathbot.json."));
		src.sendFeedback(infoLine(playKey.getBoundKeyLocalizedText(),
			"start/stop bota. Bot podchodzi na start, odtwarza trasę i wraca na początek - w kółko."));
		src.sendFeedback(infoLine(showKey.getBoundKeyLocalizedText(),
			"pokaż/ukryj particlesy trasy."));
		src.sendFeedback(Text.literal("Particlesy: zielony słup = start, ogień = koniec, białe = trasa, iskry = atak, niebieski ogień = użycie (prawy przycisk).")
			.formatted(Formatting.GRAY));
		src.sendFeedback(Text.literal("Wskazówki: zakończ nagrywanie blisko startu, żeby pętla była płynna. Otwarcie czatu lub menu zatrzymuje bota. Jeśli bot utknie albo zgubi trasę, sam się zatrzyma.")
			.formatted(Formatting.GRAY));
		src.sendFeedback(Text.literal("Klawisze zmienisz w Ustawienia -> Sterowanie -> PathBot.").formatted(Formatting.DARK_GRAY));
	}

	private Text infoLine(Text key, String desc) {
		return Text.literal("[").formatted(Formatting.DARK_GRAY)
			.append(key.copy().formatted(Formatting.YELLOW))
			.append(Text.literal("] ").formatted(Formatting.DARK_GRAY))
			.append(Text.literal(desc).formatted(Formatting.WHITE));
	}

	/** Rysuje nagrana trase particlesami (tylko po stronie klienta, widoczne tylko dla Ciebie). */
	private void drawPath(MinecraftClient client, ClientPlayerEntity p) {
		if (!showPath || frames.size() < 2) return;
		if (++particleTick % 20 != 0) return;   // END_ROD zyje ok. 3 s, wiec odswiezamy co 1 s

		double maxSq = 40.0 * 40.0;
		Vec3d pp = p.getPos();
		for (int i = 0; i < frames.size(); i++) {
			double[] f = frames.get(i);
			if (pp.squaredDistanceTo(f[0], f[1], f[2]) > maxSq) continue;
			int fl = (int) f[5];
			ParticleEffect e = null;
			if ((fl & ATTACK) != 0 && i % 2 == 0) e = ParticleTypes.CRIT;                 // miejsca ataku / kopania
			else if ((fl & USE) != 0 && i % 2 == 0) e = ParticleTypes.SOUL_FIRE_FLAME;    // miejsca uzycia / stawiania
			else if (i % 4 == 0) e = ParticleTypes.END_ROD;                               // sama trasa
			if (e != null) client.particleManager.addParticle(e, f[0], f[1] + 0.1, f[2], 0, 0, 0);
		}

		// slupy: start = zielone, koniec = ogien
		double[] s = frames.get(0);
		double[] en = frames.get(frames.size() - 1);
		for (int k = 0; k < 4; k++) {
			client.particleManager.addParticle(ParticleTypes.HAPPY_VILLAGER, s[0], s[1] + 0.3 + k * 0.5, s[2], 0, 0, 0);
			client.particleManager.addParticle(ParticleTypes.FLAME, en[0], en[1] + 0.3 + k * 0.5, en[2], 0, 0, 0);
		}
	}

	// ---------------------------------------------------------------- nagrywanie

	private void toggleRecording(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (state == State.RECORDING) {
			state = State.IDLE;
			save();
			msg(player, "Nagrywanie zakonczone. Ticki: " + frames.size() + " (~" + frames.size() / 20 + " s)");
		} else {
			stopBot(client);
			frames = new ArrayList<>();
			state = State.RECORDING;
			msg(player, "Nagrywam... (R aby zakonczyc). Zakoncz blisko miejsca startu, zeby petla byla plynna.");
		}
	}

	private void record(MinecraftClient client, ClientPlayerEntity p) {
		GameOptions o = client.options;
		int flags = 0;
		if (o.forwardKey.isPressed()) flags |= FWD;
		if (o.backKey.isPressed()) flags |= BACK;
		if (o.leftKey.isPressed()) flags |= LEFT;
		if (o.rightKey.isPressed()) flags |= RIGHT;
		if (o.jumpKey.isPressed()) flags |= JUMP;
		if (p.isSprinting()) flags |= SPRINT;
		if (o.sneakKey.isPressed()) flags |= SNEAK;
		if (o.attackKey.isPressed()) flags |= ATTACK;
		if (o.useKey.isPressed()) flags |= USE;
		frames.add(new double[]{p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), flags,
			p.getInventory().selectedSlot});
	}

	// ---------------------------------------------------------------- odtwarzanie

	private void togglePlaying(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (state == State.APPROACH || state == State.REPLAY) {
			stopBot(client);
			msg(player, "Bot zatrzymany (przejsc petli: " + loops + ").");
			return;
		}
		if (state == State.RECORDING) {
			state = State.IDLE;
			save();
		}
		if (frames.size() < 5) {
			msg(player, "Brak nagranej trasy (nagraj ja klawiszem R).");
			return;
		}
		loops = 0;
		beginApproach(player);
		msg(player, "Bot rusza (P aby zatrzymac).");
	}

	private void beginApproach(ClientPlayerEntity player) {
		state = State.APPROACH;
		stuck = 0;
		lastPos = player.getPos();
	}

	/** Dochodzi do pierwszej ramki nagrania (dotyczy startu i powrotu po koncu petli). */
	private void approach(MinecraftClient client, ClientPlayerEntity p) {
		if (abortIfScreen(client)) return;
		GameOptions o = client.options;
		double[] s = frames.get(0);
		Vec3d pos = p.getPos();
		double dx = s[0] - pos.x, dz = s[2] - pos.z;
		double distH = Math.sqrt(dx * dx + dz * dz);

		if (distH < START_REACH && Math.abs(s[1] - pos.y) < 1.5) {
			releaseKeys(client);
			idx = 0;
			state = State.REPLAY;
			return;
		}

		p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
		p.setPitch(0.0f);
		o.forwardKey.setPressed(true);
		o.sprintKey.setPressed(distH > 3.0);
		o.jumpKey.setPressed(p.isOnGround() && (s[1] > pos.y + 0.5 || p.horizontalCollision) || p.isTouchingWater());

		if (pos.squaredDistanceTo(lastPos) < 0.0025) stuck++; else stuck = 0;
		lastPos = pos;
		if (stuck > STUCK_LIMIT) {
			stopBot(client);
			msg(p, "Bot utknal w drodze na start - zatrzymano.");
		}
	}

	private void replay(MinecraftClient client, ClientPlayerEntity p) {
		if (abortIfScreen(client)) return;

		// kontrola odchylki od nagrania (pozycja po poprzednim ticku)
		if (idx > 0) {
			double[] exp = frames.get(idx - 1);
			double dev = p.getPos().distanceTo(new Vec3d(exp[0], exp[1], exp[2]));
			if (dev > RESYNC_DIST) {
				int best = nearestFrame(p.getPos());
				double[] f = frames.get(best);
				double d2 = p.getPos().distanceTo(new Vec3d(f[0], f[1], f[2]));
				if (d2 > LOST_DIST) {
					stopBot(client);
					msg(p, "Bot zgubil trase - zatrzymano.");
					return;
				}
				idx = best;
			}
		}

		// koniec nagrania -> wroc na start i jedz od nowa
		if (idx >= frames.size()) {
			loops++;
			releaseKeys(client);
			beginApproach(p);
			return;
		}

		double[] f = frames.get(idx++);
		int flags = (int) f[5];
		GameOptions o = client.options;
		p.setYaw((float) f[3]);
		p.setPitch((float) f[4]);
		o.forwardKey.setPressed((flags & FWD) != 0);
		o.backKey.setPressed((flags & BACK) != 0);
		o.leftKey.setPressed((flags & LEFT) != 0);
		o.rightKey.setPressed((flags & RIGHT) != 0);
		o.jumpKey.setPressed((flags & JUMP) != 0);
		o.sprintKey.setPressed((flags & SPRINT) != 0);
		o.sneakKey.setPressed((flags & SNEAK) != 0);

		// klikniecia: przy wcisnieciu (zbocze) symulujemy prawdziwy klik, zeby dzialal tez atak na moby
		applyClick(o.attackKey, (flags & ATTACK) != 0, (lastFlags & ATTACK) != 0);
		applyClick(o.useKey, (flags & USE) != 0, (lastFlags & USE) != 0);
		lastFlags = flags;

		// wybrany slot na pasku
		int slot = (int) f[6];
		if (slot >= 0 && slot <= 8) p.getInventory().selectedSlot = slot;
	}

	private void applyClick(KeyBinding kb, boolean down, boolean wasDown) {
		kb.setPressed(down);
		if (down && !wasDown) {
			KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey()));
		}
	}

	private int nearestFrame(Vec3d pos) {
		int best = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < frames.size(); i++) {
			double[] f = frames.get(i);
			double d = pos.squaredDistanceTo(f[0], f[1], f[2]);
			if (d < bd) { bd = d; best = i; }
		}
		return best;
	}

	// ---------------------------------------------------------------- pomocnicze

	private boolean abortIfScreen(MinecraftClient client) {
		if (client.currentScreen != null) {
			stopBot(client);
			return true;
		}
		return false;
	}

	private void stopBot(MinecraftClient client) {
		if (state == State.APPROACH || state == State.REPLAY) releaseKeys(client);
		if (state != State.RECORDING) state = State.IDLE;
	}

	private void releaseKeys(MinecraftClient client) {
		GameOptions o = client.options;
		o.forwardKey.setPressed(false);
		o.backKey.setPressed(false);
		o.leftKey.setPressed(false);
		o.rightKey.setPressed(false);
		o.jumpKey.setPressed(false);
		o.sprintKey.setPressed(false);
		o.sneakKey.setPressed(false);
		o.attackKey.setPressed(false);
		o.useKey.setPressed(false);
		lastFlags = 0;
	}

	private void msg(ClientPlayerEntity p, String s) {
		p.sendMessage(Text.literal("[PathBot] " + s), true);
	}

	private void save() {
		try {
			Files.writeString(file, gson.toJson(frames));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void load() {
		try {
			if (Files.exists(file)) {
				double[][] arr = gson.fromJson(Files.readString(file), double[][].class);
				frames = new ArrayList<>();
				if (arr != null) for (double[] f : arr) {
					if (f.length >= 7) frames.add(f);
					else if (f.length == 6) frames.add(new double[]{f[0], f[1], f[2], f[3], f[4], f[5], -1});
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
