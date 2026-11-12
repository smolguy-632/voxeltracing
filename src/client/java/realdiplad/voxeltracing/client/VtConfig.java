package realdiplad.voxeltracing.client;

import net.fabricmc.loader.api.FabricLoader;
import realdiplad.voxeltracing.Voxeltracing;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Tiny properties-file config + quality presets. Stored in config/voxeltracing.properties. */
public final class VtConfig {

	public enum Quality {
		//        chunks  sections  rayRange  bakeBudgetMs  sunSteps/day  lightRays/voxel
		LOW(3, 2, 16, 0.8f, 48, 2),
		MEDIUM(5, 3, 24, 1.5f, 72, 3),
		HIGH(8, 4, 32, 2.5f, 96, 4);

		public final int horizontalRadius;
		public final int verticalRadius;
		public final int rayRange;
		public final float bakeBudgetMs;
		public final int sunSteps;
		public final int lightRays;

		Quality(int h, int v, int range, float budgetMs, int sunSteps, int lightRays) {
			this.horizontalRadius = h;
			this.verticalRadius = v;
			this.rayRange = range;
			this.bakeBudgetMs = budgetMs;
			this.sunSteps = sunSteps;
			this.lightRays = lightRays;
		}

		public Quality next() {
			Quality[] all = values();
			return all[(ordinal() + 1) % all.length];
		}
	}

	private static VtConfig instance = new VtConfig();

	public boolean enabled = true;
	public Quality quality = Quality.MEDIUM;
	public boolean sunShadows = true;
	public boolean blockLight = true;
	/** Darkness (0..1) of a face that is occluded from the sun by another voxel. */
	public float shadowStrength = 0.42f;
	/** Darkness (0..1) of a face pointing away from the sun. */
	public float selfShade = 0.22f;
	/** Strength (0..1) of the warm tint on faces with line-of-sight to a light source. */
	public float warmStrength = 0.14f;
	/** Darkness (0..1) where vanilla light "leaks" around corners but rays are blocked. */
	public float torchShadow = 0.35f;

	public static VtConfig get() {
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("voxeltracing.properties");
	}

	public static void load() {
		VtConfig c = new VtConfig();
		Path p = path();
		if (Files.exists(p)) {
			Properties props = new Properties();
			try (Reader r = Files.newBufferedReader(p)) {
				props.load(r);
				c.enabled = bool(props, "enabled", c.enabled);
				c.sunShadows = bool(props, "sunShadows", c.sunShadows);
				c.blockLight = bool(props, "blockLight", c.blockLight);
				c.shadowStrength = flt(props, "shadowStrength", c.shadowStrength);
				c.selfShade = flt(props, "selfShade", c.selfShade);
				c.warmStrength = flt(props, "warmStrength", c.warmStrength);
				c.torchShadow = flt(props, "torchShadow", c.torchShadow);
				try {
					c.quality = Quality.valueOf(props.getProperty("quality", "MEDIUM").trim().toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException ignored) {
					// keep default
				}
			} catch (IOException e) {
				Voxeltracing.LOGGER.warn("Could not read config, using defaults", e);
			}
		}
		instance = c;
		c.save();
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("quality", quality.name());
		props.setProperty("sunShadows", Boolean.toString(sunShadows));
		props.setProperty("blockLight", Boolean.toString(blockLight));
		props.setProperty("shadowStrength", Float.toString(shadowStrength));
		props.setProperty("selfShade", Float.toString(selfShade));
		props.setProperty("warmStrength", Float.toString(warmStrength));
		props.setProperty("torchShadow", Float.toString(torchShadow));
		try (Writer w = Files.newBufferedWriter(path())) {
			props.store(w, "Voxel Tracing - quality: LOW | MEDIUM | HIGH. Strengths are 0.0 - 1.0.");
		} catch (IOException e) {
			Voxeltracing.LOGGER.warn("Could not save config", e);
		}
	}

	private static boolean bool(Properties p, String k, boolean def) {
		String v = p.getProperty(k);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	private static float flt(Properties p, String k, float def) {
		String v = p.getProperty(k);
		if (v == null) return def;
		try {
			float f = Float.parseFloat(v.trim());
			return Math.max(0f, Math.min(1f, f));
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
