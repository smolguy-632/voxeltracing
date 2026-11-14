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
		//        chunks sections shadowRange giRange giRays bakeBudgetMs sunSteps maxLights lightRadius
		LOW(3, 2, 32, 12, 3, 0.8f, 24, 2, 10),
		MEDIUM(5, 3, 48, 16, 6, 1.5f, 32, 3, 12),
		HIGH(8, 4, 64, 24, 12, 2.5f, 48, 4, 14);

		public final int horizontalRadius;
		public final int verticalRadius;
		/** Max length (blocks) of a sun shadow ray. */
		public final int shadowRange;
		/** Max length (blocks) of a global-illumination ray. */
		public final int giRange;
		/** Cosine-weighted hemisphere rays per vertex. */
		public final int giRays;
		public final float bakeBudgetMs;
		public final int sunSteps;
		/** Nearest light sources that get a shadow ray per vertex. */
		public final int maxLights;
		public final int lightRadius;

		Quality(int h, int v, int shadowRange, int giRange, int giRays, float budgetMs, int sunSteps,
				int maxLights, int lightRadius) {
			this.horizontalRadius = h;
			this.verticalRadius = v;
			this.shadowRange = shadowRange;
			this.giRange = giRange;
			this.giRays = giRays;
			this.bakeBudgetMs = budgetMs;
			this.sunSteps = sunSteps;
			this.maxLights = maxLights;
			this.lightRadius = lightRadius;
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
	/** Global illumination: sky occlusion + coloured light bouncing off sunlit surfaces. */
	public boolean gi = true;
	/** 0..1 scale of the GI contribution. */
	public float giStrength = 0.8f;
	/** How much of the daylight look comes from the sun (what a fully shadowed face loses), 0..1. */
	public float shadowStrength = 0.5f;
	/** true: shadow edges are resolved down to 1/16 block, false: 1/4 block. */
	public boolean crispShadows = true;
	/** Sideways tilt of the sun path. 0 matches the sun drawn by the vanilla sky. */
	public float sunTilt = 0.0f;
	/** Strength (0..1) of the coloured tint from light sources with line of sight. */
	public float warmStrength = 0.14f;
	/** Darkness (0..1) of torch-lit faces that a light source cannot actually see. */
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
				c.gi = bool(props, "gi", c.gi);
				c.giStrength = flt(props, "giStrength", c.giStrength);
				c.shadowStrength = flt(props, "shadowStrength", c.shadowStrength);
				c.crispShadows = bool(props, "crispShadows", c.crispShadows);
				c.sunTilt = flt(props, "sunTilt", c.sunTilt);
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
		props.setProperty("gi", Boolean.toString(gi));
		props.setProperty("giStrength", Float.toString(giStrength));
		props.setProperty("shadowStrength", Float.toString(shadowStrength));
		props.setProperty("crispShadows", Boolean.toString(crispShadows));
		props.setProperty("sunTilt", Float.toString(sunTilt));
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
