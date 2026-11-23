package de.caritas.cob.userservice.api.service.email.layout;

/**
 * The HCT colour space (CAM16 hue and chroma, CIE L* tone) as far as the mail colour rules need it
 * (ORISO-UserService#1252).
 *
 * <p>This is a line-by-line Java port of the parts of {@code @material/material-color-utilities}
 * 0.4.0 (Apache License 2.0, Copyright 2021 Google LLC) that ORISO-Frontend's theme engine calls
 * for a tenant's primary colour: {@code Hct.fromInt}, {@code TonalPalette.fromInt(..).tone(..)},
 * hence {@code Cam16.fromInt} (default viewing conditions) and {@code HctSolver.solveToInt}. The
 * port exists so a mail derives the button label colour exactly as the web app does (white or the
 * same-hue tone 10), see {@link EmailColors#onPrimary(String)}. Parity is guarded by {@code
 * EmailColorsParityTest} against the Frontend-owned golden fixture.
 *
 * <p>Deliberately package-private and not a general colour library: nothing here is configurable.
 */
final class Hct {

  private static final double[][] SCALED_DISCOUNT_FROM_LINRGB = {
    {0.001200833568784504, 0.002389694492170889, 0.0002795742885861124},
    {0.0005891086651375999, 0.0029785502573438758, 0.0003270666104008398},
    {0.00010146692491640572, 0.0005364214359186694, 0.0032979401770712076},
  };

  private static final double[][] LINRGB_FROM_SCALED_DISCOUNT = {
    {1373.2198709594231, -1100.4251190754821, -7.278681089101213},
    {-271.815969077903, 559.6580465940733, -32.46047482791194},
    {1.9622899599665666, -57.173814538844006, 308.7233197812385},
  };

  private static final double[] Y_FROM_LINRGB = {0.2126, 0.7152, 0.0722};

  private static final double[] CRITICAL_PLANES = {
    0.015176349177441876, 0.045529047532325624, 0.07588174588720938, 0.10623444424209313,
    0.13658714259697685, 0.16693984095186062, 0.19729253930674434, 0.2276452376616281,
    0.2579979360165119, 0.28835063437139563, 0.3188300904430532, 0.350925934958123,
    0.3848314933096426, 0.42057480301049466, 0.458183274052838, 0.4976837250274023,
    0.5391024159806381, 0.5824650784040898, 0.6277969426914107, 0.6751227633498623,
    0.7244668422128921, 0.775853049866786, 0.829304845476233, 0.8848452951698498,
    0.942497089126609, 1.0022825574869039, 1.0642236851973577, 1.1283421258858297,
    1.1946592148522128, 1.2631959812511864, 1.3339731595349034, 1.407011200216447,
    1.4823302800086415, 1.5599503113873272, 1.6398909516233677, 1.7221716113234105,
    1.8068114625156377, 1.8938294463134073, 1.9832442801866852, 2.075074464868551,
    2.1693382909216234, 2.2660538449872063, 2.36523901573795, 2.4669114995532007,
    2.5710888059345764, 2.6777882626779785, 2.7870270208169257, 2.898822059350997,
    3.0131901897720907, 3.1301480604002863, 3.2497121605402226, 3.3718988244681087,
    3.4967242352587946, 3.624204428461639, 3.754355295633311, 3.887192587735158,
    4.022731918402185, 4.160988767090289, 4.301978482107941, 4.445716283538092,
    4.592217266055746, 4.741496401646282, 4.893568542229298, 5.048448422192488,
    5.20615066083972, 5.3666897647573375, 5.5300801301023865, 5.696336044816294,
    5.865471690767354, 6.037501145825082, 6.212438385869475, 6.390297286737924,
    6.571091626112461, 6.7548350853498045, 6.941541251256611, 7.131223617812143,
    7.323895587840543, 7.5195704746346665, 7.7182615035334345, 7.919981813454504,
    8.124744458384042, 8.332562408825165, 8.543448553206703, 8.757415699253682,
    8.974476575321063, 9.194643831691977, 9.417930041841839, 9.644347703669503,
    9.873909240696694, 10.106627003236781, 10.342513269534024, 10.58158024687427,
    10.8238400726681, 11.069304815507364, 11.317986476196008, 11.569896988756009,
    11.825048221409341, 12.083451977536606, 12.345119996613247, 12.610063955123938,
    12.878295467455942, 13.149826086772048, 13.42466730586372, 13.702830557985108,
    13.984327217668513, 14.269168601521828, 14.55736596900856, 14.848930523210871,
    15.143873411576273, 15.44220572664832, 15.743938506781891, 16.04908273684337,
    16.35764934889634, 16.66964922287304, 16.985093187232053, 17.30399201960269,
    17.62635644741625, 17.95219714852476, 18.281524751807332, 18.614349837764564,
    18.95068293910138, 19.290534541298456, 19.633915083172692, 19.98083495742689,
    20.331304511189067, 20.685334046541502, 21.042933821039977, 21.404114048223256,
    21.76888489811322, 22.137256497705877, 22.50923893145328, 22.884842241736916,
    23.264076429332462, 23.6469514538663, 24.033477234264016, 24.42366364919083,
    24.817520537484558, 25.21505769858089, 25.61628489293138, 26.021211842414342,
    26.429848230738664, 26.842203703840827, 27.258287870275353, 27.678110301598522,
    28.10168053274597, 28.529008062403893, 28.96010235337422, 29.39497283293396,
    29.83362889318845, 30.276079891419332, 30.722335150426627, 31.172403958865512,
    31.62629557157785, 32.08401920991837, 32.54558406207592, 33.010999283389665,
    33.4802739966603, 33.953417292456834, 34.430438229418264, 34.911345834551085,
    35.39614910352207, 35.88485700094671, 36.37747846067349, 36.87402238606382,
    37.37449765026789, 37.87891309649659, 38.38727753828926, 38.89959975977785,
    39.41588851594697, 39.93615253289054, 40.460400508064545, 40.98864111053629,
    41.520882981230194, 42.05713473317016, 42.597404951718396, 43.141702194811224,
    43.6900349931913, 44.24241185063697, 44.798841244188324, 45.35933162437017,
    45.92389141541209, 46.49252901546552, 47.065252796817916, 47.64207110610409,
    48.22299226451468, 48.808024568002054, 49.3971762874833, 49.9904556690408,
    50.587870934119984, 51.189430279724725, 51.79514187861014, 52.40501387947288,
    53.0190544071392, 53.637271562750364, 54.259673423945976, 54.88626804504493,
    55.517063457223934, 56.15206766869424, 56.79128866487574, 57.43473440856916,
    58.08241284012621, 58.734331877617365, 59.39049941699807, 60.05092333227251,
    60.715611475655585, 61.38457167773311, 62.057811747619894, 62.7353394731159,
    63.417162620860914, 64.10328893648692, 64.79372614476921, 65.48848194977529,
    66.18756403501224, 66.89098006357258, 67.59873767827808, 68.31084450182222,
    69.02730813691093, 69.74813616640164, 70.47333615344107, 71.20291564160104,
    71.93688215501312, 72.67524319850172, 73.41800625771542, 74.16517879925733,
    74.9167682708136, 75.67278210128072, 76.43322770089146, 77.1981124613393,
    77.96744375590167, 78.74122893956174, 79.51947534912904, 80.30219030335869,
    81.08938110306934, 81.88105503125999, 82.67721935322541, 83.4778813166706,
    84.28304815182372, 85.09272707154808, 85.90692527145302, 86.72564993000343,
    87.54890820862819, 88.3767072518277, 89.2090541872801, 90.04595612594655,
    90.88742016217518, 91.73345337380438, 92.58406282226491, 93.43925555268066,
    94.29903859396902, 95.16341895893969, 96.03240364439274, 96.9059996312159,
    97.78421388448044, 98.6670533535366, 99.55452497210776,
  };

  // ViewingConditions.DEFAULT: D65 white point, adapting luminance (200/pi) * Y(L*=50) / 100,
  // background L* 50, average surround, no discounting of the illuminant.
  private static final double[] WHITE_POINT_D65 = {95.047, 100.0, 108.883};
  private static final double ADAPTING_LUMINANCE = (200.0 / Math.PI) * yFromLstar(50.0) / 100.0;
  private static final double SURROUND_F = 0.8 + 2.0 / 10.0;
  private static final double VC_C = lerp(0.59, 0.69, (SURROUND_F - 0.9) * 10.0);
  private static final double VC_D =
      clamp01(SURROUND_F * (1.0 - (1.0 / 3.6) * Math.exp((-ADAPTING_LUMINANCE - 42.0) / 92.0)));
  private static final double VC_NC = SURROUND_F;
  private static final double[] VC_RGB_D = rgbD();
  private static final double VC_K = 1.0 / (5.0 * ADAPTING_LUMINANCE + 1.0);
  private static final double VC_K4 = VC_K * VC_K * VC_K * VC_K;
  private static final double VC_K4F = 1.0 - VC_K4;
  private static final double VC_FL =
      VC_K4 * ADAPTING_LUMINANCE + 0.1 * VC_K4F * VC_K4F * Math.cbrt(5.0 * ADAPTING_LUMINANCE);
  private static final double VC_N = yFromLstar(50.0) / WHITE_POINT_D65[1];
  private static final double VC_Z = 1.48 + Math.sqrt(VC_N);
  private static final double VC_NBB = 0.725 / Math.pow(VC_N, 0.2);
  private static final double VC_NCB = VC_NBB;
  private static final double VC_FL_ROOT = Math.pow(VC_FL, 0.25);
  private static final double VC_AW = achromaticResponseOfWhite();

  private final int argb;
  private final double hue;
  private final double chroma;
  private final double tone;

  private Hct(int argb) {
    this.argb = argb;
    double[] cam = cam16HueChroma(argb);
    this.hue = cam[0];
    this.chroma = cam[1];
    this.tone = lstarFromArgb(argb);
  }

  static Hct fromArgb(int argb) {
    return new Hct(argb);
  }

  /** {@code Hct.fromInt(0xff000000 | rgb)} for a {@code #rrggbb} value. */
  static Hct fromHex(String normalizedHex) {
    return new Hct(0xff000000 | Integer.parseInt(normalizedHex.substring(1), 16));
  }

  /** Hue in CAM16 degrees, {@code 0 <= hue < 360}. */
  double hue() {
    return hue;
  }

  double chroma() {
    return chroma;
  }

  /** CIE L*, {@code 0..100}. */
  double tone() {
    return tone;
  }

  int argb() {
    return argb;
  }

  /**
   * {@code TonalPalette.fromInt(argb).tone(tone)} as a {@code #rrggbb} string: the colour of the
   * same hue and chroma at another tone.
   */
  String toneHex(int newTone) {
    return hexOf(solveToInt(hue, chroma, newTone));
  }

  static String hexOf(int argb) {
    return String.format("#%06x", argb & 0xffffff);
  }

  /** {@code Contrast.ratioOfTones(a, b)} of the library: ratio of the two Y values, 1..21. */
  static double ratioOfTones(double toneA, double toneB) {
    double yA = yFromLstar(toneA);
    double yB = yFromLstar(toneB);
    double lighter = Math.max(yA, yB);
    double darker = Math.min(yA, yB);
    return (lighter + 5.0) / (darker + 5.0);
  }

  // ---------------------------------------------------------------- color utils

  private static double linearized(int rgbComponent) {
    double normalized = rgbComponent / 255.0;
    if (normalized <= 0.040449936) {
      return normalized / 12.92 * 100.0;
    }
    return Math.pow((normalized + 0.055) / 1.055, 2.4) * 100.0;
  }

  private static int delinearized(double rgbComponent) {
    double normalized = rgbComponent / 100.0;
    double delinearized;
    if (normalized <= 0.0031308) {
      delinearized = normalized * 12.92;
    } else {
      delinearized = 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
    }
    return clampInt(0, 255, Math.round(delinearized * 255.0));
  }

  private static int clampInt(int min, int max, long input) {
    return (int) Math.max(min, Math.min(max, input));
  }

  private static int argbFromRgb(int red, int green, int blue) {
    return (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
  }

  private static int argbFromLinrgb(double[] linrgb) {
    return argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2]));
  }

  private static int argbFromLstar(double lstar) {
    int component = delinearized(yFromLstar(lstar));
    return argbFromRgb(component, component, component);
  }

  private static double lstarFromArgb(int argb) {
    double y =
        0.2126 * linearized((argb >> 16) & 255)
            + 0.7152 * linearized((argb >> 8) & 255)
            + 0.0722 * linearized(argb & 255);
    return 116.0 * labF(y / 100.0) - 16.0;
  }

  private static double yFromLstar(double lstar) {
    return 100.0 * labInvf((lstar + 16.0) / 116.0);
  }

  private static double labF(double t) {
    double e = 216.0 / 24389.0;
    double kappa = 24389.0 / 27.0;
    return t > e ? Math.cbrt(t) : (kappa * t + 16) / 116;
  }

  private static double labInvf(double ft) {
    double e = 216.0 / 24389.0;
    double kappa = 24389.0 / 27.0;
    double ft3 = ft * ft * ft;
    return ft3 > e ? ft3 : (116 * ft - 16) / kappa;
  }

  private static double signum(double num) {
    return num < 0 ? -1 : (num == 0 ? 0 : 1);
  }

  private static double lerp(double start, double stop, double amount) {
    return (1.0 - amount) * start + amount * stop;
  }

  private static double clamp01(double value) {
    return value > 1.0 ? 1.0 : (value < 0.0 ? 0.0 : value);
  }

  private static double sanitizeDegrees(double degrees) {
    double result = degrees % 360.0;
    return result < 0 ? result + 360.0 : result;
  }

  private static double[] matrixMultiply(double[] row, double[][] matrix) {
    return new double[] {
      row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2],
      row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2],
      row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2]
    };
  }

  // ------------------------------------------------------------ viewing conditions

  private static double[] rgbD() {
    double[] xyz = WHITE_POINT_D65;
    double rW = xyz[0] * 0.401288 + xyz[1] * 0.650173 + xyz[2] * -0.051461;
    double gW = xyz[0] * -0.250268 + xyz[1] * 1.204414 + xyz[2] * 0.045854;
    double bW = xyz[0] * -0.002079 + xyz[1] * 0.048952 + xyz[2] * 0.953127;
    return new double[] {
      VC_D * (100.0 / rW) + 1.0 - VC_D,
      VC_D * (100.0 / gW) + 1.0 - VC_D,
      VC_D * (100.0 / bW) + 1.0 - VC_D
    };
  }

  private static double achromaticResponseOfWhite() {
    double[] xyz = WHITE_POINT_D65;
    double rW = xyz[0] * 0.401288 + xyz[1] * 0.650173 + xyz[2] * -0.051461;
    double gW = xyz[0] * -0.250268 + xyz[1] * 1.204414 + xyz[2] * 0.045854;
    double bW = xyz[0] * -0.002079 + xyz[1] * 0.048952 + xyz[2] * 0.953127;
    double[] factors = {
      Math.pow((VC_FL * VC_RGB_D[0] * rW) / 100.0, 0.42),
      Math.pow((VC_FL * VC_RGB_D[1] * gW) / 100.0, 0.42),
      Math.pow((VC_FL * VC_RGB_D[2] * bW) / 100.0, 0.42)
    };
    double[] rgbA = {
      (400.0 * factors[0]) / (factors[0] + 27.13),
      (400.0 * factors[1]) / (factors[1] + 27.13),
      (400.0 * factors[2]) / (factors[2] + 27.13)
    };
    return (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * VC_NBB;
  }

  // ------------------------------------------------------------------------ CAM16

  /** {@code Cam16.fromInt(argb)}, reduced to the hue (degrees) and chroma the tone solver needs. */
  private static double[] cam16HueChroma(int argb) {
    double redL = linearized((argb >> 16) & 255);
    double greenL = linearized((argb >> 8) & 255);
    double blueL = linearized(argb & 255);
    double x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL;
    double y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL;
    double z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL;
    double rC = 0.401288 * x + 0.650173 * y - 0.051461 * z;
    double gC = -0.250268 * x + 1.204414 * y + 0.045854 * z;
    double bC = -0.002079 * x + 0.048952 * y + 0.953127 * z;
    double rD = VC_RGB_D[0] * rC;
    double gD = VC_RGB_D[1] * gC;
    double bD = VC_RGB_D[2] * bC;
    double rAF = Math.pow((VC_FL * Math.abs(rD)) / 100.0, 0.42);
    double gAF = Math.pow((VC_FL * Math.abs(gD)) / 100.0, 0.42);
    double bAF = Math.pow((VC_FL * Math.abs(bD)) / 100.0, 0.42);
    double rA = (signum(rD) * 400.0 * rAF) / (rAF + 27.13);
    double gA = (signum(gD) * 400.0 * gAF) / (gAF + 27.13);
    double bA = (signum(bD) * 400.0 * bAF) / (bAF + 27.13);
    double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
    double b = (rA + gA - 2.0 * bA) / 9.0;
    double u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0;
    double p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0;
    double atanDegrees = (Math.atan2(b, a) * 180.0) / Math.PI;
    double hue = sanitizeDegrees(atanDegrees);
    double ac = p2 * VC_NBB;
    double j = 100.0 * Math.pow(ac / VC_AW, VC_C * VC_Z);
    double huePrime = hue < 20.14 ? hue + 360 : hue;
    double eHue = 0.25 * (Math.cos((huePrime * Math.PI) / 180.0 + 2.0) + 3.8);
    double p1 = (50000.0 / 13.0) * eHue * VC_NC * VC_NCB;
    double t = (p1 * Math.sqrt(a * a + b * b)) / (u + 0.305);
    double alpha = Math.pow(t, 0.9) * Math.pow(1.64 - Math.pow(0.29, VC_N), 0.73);
    double chroma = alpha * Math.sqrt(j / 100.0);
    return new double[] {hue, chroma};
  }

  // ------------------------------------------------------------------- HCT solver

  private static double sanitizeRadians(double angle) {
    return (angle + Math.PI * 8) % (Math.PI * 2);
  }

  private static double trueDelinearized(double rgbComponent) {
    double normalized = rgbComponent / 100.0;
    double delinearized =
        normalized <= 0.0031308
            ? normalized * 12.92
            : 1.055 * Math.pow(normalized, 1.0 / 2.4) - 0.055;
    return delinearized * 255.0;
  }

  private static double chromaticAdaptation(double component) {
    double af = Math.pow(Math.abs(component), 0.42);
    return signum(component) * 400.0 * af / (af + 27.13);
  }

  private static double hueOf(double[] linrgb) {
    double[] scaledDiscount = matrixMultiply(linrgb, SCALED_DISCOUNT_FROM_LINRGB);
    double rA = chromaticAdaptation(scaledDiscount[0]);
    double gA = chromaticAdaptation(scaledDiscount[1]);
    double bA = chromaticAdaptation(scaledDiscount[2]);
    double a = (11.0 * rA + -12.0 * gA + bA) / 11.0;
    double b = (rA + gA - 2.0 * bA) / 9.0;
    return Math.atan2(b, a);
  }

  private static boolean areInCyclicOrder(double a, double b, double c) {
    return sanitizeRadians(b - a) < sanitizeRadians(c - a);
  }

  private static double intercept(double source, double mid, double target) {
    return (mid - source) / (target - source);
  }

  private static double[] lerpPoint(double[] source, double t, double[] target) {
    return new double[] {
      source[0] + (target[0] - source[0]) * t,
      source[1] + (target[1] - source[1]) * t,
      source[2] + (target[2] - source[2]) * t
    };
  }

  private static double[] setCoordinate(
      double[] source, double coordinate, double[] target, int axis) {
    double t = intercept(source[axis], coordinate, target[axis]);
    return lerpPoint(source, t, target);
  }

  private static boolean isBounded(double x) {
    return 0.0 <= x && x <= 100.0;
  }

  private static double[] nthVertex(double y, int n) {
    double kR = Y_FROM_LINRGB[0];
    double kG = Y_FROM_LINRGB[1];
    double kB = Y_FROM_LINRGB[2];
    double coordA = n % 4 <= 1 ? 0.0 : 100.0;
    double coordB = n % 2 == 0 ? 0.0 : 100.0;
    if (n < 4) {
      double g = coordA;
      double b = coordB;
      double r = (y - g * kG - b * kB) / kR;
      return isBounded(r) ? new double[] {r, g, b} : new double[] {-1.0, -1.0, -1.0};
    } else if (n < 8) {
      double b = coordA;
      double r = coordB;
      double g = (y - r * kR - b * kB) / kG;
      return isBounded(g) ? new double[] {r, g, b} : new double[] {-1.0, -1.0, -1.0};
    }
    double r = coordA;
    double g = coordB;
    double b = (y - r * kR - g * kG) / kB;
    return isBounded(b) ? new double[] {r, g, b} : new double[] {-1.0, -1.0, -1.0};
  }

  private static double[][] bisectToSegment(double y, double targetHue) {
    double[] left = {-1.0, -1.0, -1.0};
    double[] right = left;
    double leftHue = 0.0;
    double rightHue = 0.0;
    boolean initialized = false;
    boolean uncut = true;
    for (int n = 0; n < 12; n++) {
      double[] mid = nthVertex(y, n);
      if (mid[0] < 0) {
        continue;
      }
      double midHue = hueOf(mid);
      if (!initialized) {
        left = mid;
        right = mid;
        leftHue = midHue;
        rightHue = midHue;
        initialized = true;
        continue;
      }
      if (uncut || areInCyclicOrder(leftHue, midHue, rightHue)) {
        uncut = false;
        if (areInCyclicOrder(leftHue, targetHue, midHue)) {
          right = mid;
          rightHue = midHue;
        } else {
          left = mid;
          leftHue = midHue;
        }
      }
    }
    return new double[][] {left, right};
  }

  private static double[] midpoint(double[] a, double[] b) {
    return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
  }

  private static int criticalPlaneBelow(double x) {
    return (int) Math.floor(x - 0.5);
  }

  private static int criticalPlaneAbove(double x) {
    return (int) Math.ceil(x - 0.5);
  }

  private static double[] bisectToLimit(double y, double targetHue) {
    double[][] segment = bisectToSegment(y, targetHue);
    double[] left = segment[0];
    double leftHue = hueOf(left);
    double[] right = segment[1];
    for (int axis = 0; axis < 3; axis++) {
      if (left[axis] != right[axis]) {
        int lPlane;
        int rPlane;
        if (left[axis] < right[axis]) {
          lPlane = criticalPlaneBelow(trueDelinearized(left[axis]));
          rPlane = criticalPlaneAbove(trueDelinearized(right[axis]));
        } else {
          lPlane = criticalPlaneAbove(trueDelinearized(left[axis]));
          rPlane = criticalPlaneBelow(trueDelinearized(right[axis]));
        }
        for (int i = 0; i < 8; i++) {
          if (Math.abs(rPlane - lPlane) <= 1) {
            break;
          }
          int mPlane = (int) Math.floor((lPlane + rPlane) / 2.0);
          double midPlaneCoordinate = CRITICAL_PLANES[mPlane];
          double[] mid = setCoordinate(left, midPlaneCoordinate, right, axis);
          double midHue = hueOf(mid);
          if (areInCyclicOrder(leftHue, targetHue, midHue)) {
            right = mid;
            rPlane = mPlane;
          } else {
            left = mid;
            leftHue = midHue;
            lPlane = mPlane;
          }
        }
      }
    }
    return midpoint(left, right);
  }

  private static double inverseChromaticAdaptation(double adapted) {
    double adaptedAbs = Math.abs(adapted);
    double base = Math.max(0, 27.13 * adaptedAbs / (400.0 - adaptedAbs));
    return signum(adapted) * Math.pow(base, 1.0 / 0.42);
  }

  /** Returns the ARGB colour for hue, chroma and Y, or {@code 0} when none is in gamut. */
  private static int findResultByJ(double hueRadians, double chroma, double y) {
    double j = Math.sqrt(y) * 11.0;
    double tInnerCoeff = 1 / Math.pow(1.64 - Math.pow(0.29, VC_N), 0.73);
    double eHue = 0.25 * (Math.cos(hueRadians + 2.0) + 3.8);
    double p1 = eHue * (50000.0 / 13.0) * VC_NC * VC_NCB;
    double hSin = Math.sin(hueRadians);
    double hCos = Math.cos(hueRadians);
    for (int iterationRound = 0; iterationRound < 5; iterationRound++) {
      double jNormalized = j / 100.0;
      double alpha = chroma == 0.0 || j == 0.0 ? 0.0 : chroma / Math.sqrt(jNormalized);
      double t = Math.pow(alpha * tInnerCoeff, 1.0 / 0.9);
      double ac = VC_AW * Math.pow(jNormalized, 1.0 / VC_C / VC_Z);
      double p2 = ac / VC_NBB;
      double gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11 * t * hCos + 108.0 * t * hSin);
      double a = gamma * hCos;
      double b = gamma * hSin;
      double rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0;
      double gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0;
      double bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0;
      double rCScaled = inverseChromaticAdaptation(rA);
      double gCScaled = inverseChromaticAdaptation(gA);
      double bCScaled = inverseChromaticAdaptation(bA);
      double[] linrgb =
          matrixMultiply(new double[] {rCScaled, gCScaled, bCScaled}, LINRGB_FROM_SCALED_DISCOUNT);
      if (linrgb[0] < 0 || linrgb[1] < 0 || linrgb[2] < 0) {
        return 0;
      }
      double fnj =
          Y_FROM_LINRGB[0] * linrgb[0]
              + Y_FROM_LINRGB[1] * linrgb[1]
              + Y_FROM_LINRGB[2] * linrgb[2];
      if (fnj <= 0) {
        return 0;
      }
      if (iterationRound == 4 || Math.abs(fnj - y) < 0.002) {
        if (linrgb[0] > 100.01 || linrgb[1] > 100.01 || linrgb[2] > 100.01) {
          return 0;
        }
        return argbFromLinrgb(linrgb);
      }
      j = j - (fnj - y) * j / (2 * fnj);
    }
    return 0;
  }

  private static int solveToInt(double hueDegrees, double chroma, double lstar) {
    if (chroma < 0.0001 || lstar < 0.0001 || lstar > 99.9999) {
      return argbFromLstar(lstar);
    }
    double hueRadians = sanitizeDegrees(hueDegrees) / 180 * Math.PI;
    double y = yFromLstar(lstar);
    int exactAnswer = findResultByJ(hueRadians, chroma, y);
    if (exactAnswer != 0) {
      return exactAnswer;
    }
    return argbFromLinrgb(bisectToLimit(y, hueRadians));
  }
}
