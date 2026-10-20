package de.caritas.cob.userservice.api.adapters.matrix;

import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserRequestDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixInviteUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixLoginRequestDTO;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

/**
 * A Synapse that remembers who is in which room. It sits behind the real {@link
 * MatrixSynapseService} and {@link MatrixRoomClient} (as their {@link RestTemplate}), so a test
 * exercises the production gateway code end to end and Matrix answers the way the homeserver does,
 * instead of a mock that says "yes" to every join.
 *
 * <p>Rules it enforces (all taken from the Matrix spec / Synapse source):
 *
 * <ul>
 *   <li>Rooms are invite-only. A join works for a member or an invited user and is refused (403)
 *       otherwise.
 *   <li>An invite needs a joined inviter with power {@code >= invite} (0 by default); inviting a
 *       member is refused ({@code "is already in the room"}).
 *   <li>A power-level change needs a joined sender with power {@code >= 100} (the {@code
 *       m.room.power_levels} event level), cannot raise anyone above the sender and cannot touch
 *       someone at or above the sender (self excepted).
 *   <li>Leaving needs no power; kicking needs {@code >= kick} and more power than the target.
 *   <li>Leaving keeps the user's entry in the power-level {@code users} map, as in Synapse.
 *   <li>{@code POST /_synapse/admin/v1/join/{room}}: the calling admin invites the target first, so
 *       the admin has to be joined and may invite.
 *   <li>{@code POST /_synapse/admin/v1/rooms/{room}/make_room_admin}: acts through the
 *       highest-power local user in the {@code users} map who is currently joined, copies that
 *       power level to the target, and answers 400 when nobody qualifies or the user lacks the
 *       power to change power levels.
 * </ul>
 */
public final class FakeSynapseHomeserver extends RestTemplate {

  public static final String SERVER_NAME = "oriso.test";
  public static final String ADMIN_LOCALPART = "matrixadm";

  private static final String ADMIN_PASSWORD = "admin-secret";
  private static final int POWER_LEVELS_EVENT_LEVEL = 100;
  private static final int KICK_LEVEL = 50;
  private static final int INVITE_LEVEL = 0;

  private final Set<String> accounts = new LinkedHashSet<>();
  private final Map<String, Room> rooms = new LinkedHashMap<>();
  private final MatrixConfig config = new MatrixConfig();
  private int roomCounter;
  private int adminJoinRequests;
  private int makeRoomAdminRequests;
  private boolean adminJoinFailsAfterInvite;
  private boolean leavesAreRefused;
  private final List<String> impersonated = new ArrayList<>();

  private static final class Room {
    private final Set<String> joined = new LinkedHashSet<>();
    private final Set<String> invited = new LinkedHashSet<>();
    private final Set<String> left = new LinkedHashSet<>();
    private final Map<String, Integer> users = new LinkedHashMap<>();
    private int usersDefault;
    private int eventsDefault;
  }

  public FakeSynapseHomeserver() {
    config.setApiUrl("https://" + SERVER_NAME);
    config.setServerName(SERVER_NAME);
    config.setAdminUsername(ADMIN_LOCALPART);
    config.setAdminPassword(ADMIN_PASSWORD);
    config.setRegistrationSharedSecret("fake-registration-secret");
    accounts.add(adminMxid());
  }

  // ── wiring ────────────────────────────────────────────────────────────────

  /** The production gateway, talking to this homeserver. */
  public MatrixSynapseService gateway() {
    var roomClient = new MatrixRoomClient(config, this, mock(LiveChatDiagnosticMetrics.class));
    return new MatrixSynapseService(
        config,
        this,
        this,
        roomClient,
        mock(MatrixMediaClient.class),
        MatrixIdentifierRedactor.withKey("fake-synapse"));
  }

  public static String adminMxid() {
    return "@" + ADMIN_LOCALPART + ":" + SERVER_NAME;
  }

  public static String mxid(String localpart) {
    return "@" + localpart + ":" + SERVER_NAME;
  }

  // ── seeding the world ─────────────────────────────────────────────────────

  public FakeSynapseHomeserver account(String mxid) {
    accounts.add(mxid);
    return this;
  }

  /**
   * A session room as ORISO provisions it: invite-only, created by {@code creator} (power 100,
   * joined).
   */
  public String createRoom(String creator) {
    account(creator);
    String roomId = "!room" + (++roomCounter) + ":" + SERVER_NAME;
    var room = new Room();
    room.joined.add(creator);
    room.users.put(creator, 100);
    rooms.put(roomId, room);
    return roomId;
  }

  /** Seeds a member as if they had been invited and had accepted. */
  public FakeSynapseHomeserver member(String roomId, String mxid, int power) {
    account(mxid);
    var room = room(roomId);
    room.joined.add(mxid);
    room.left.remove(mxid);
    if (power != 0) {
      room.users.put(mxid, power);
    }
    return this;
  }

  /** The member leaves on their own, like the repair queue's LEAVE task does. */
  public FakeSynapseHomeserver memberLeaves(String roomId, String mxid) {
    var room = room(roomId);
    room.joined.remove(mxid);
    room.invited.remove(mxid);
    room.left.add(mxid);
    return this;
  }

  // ── what a test asserts on ────────────────────────────────────────────────

  public boolean isMember(String roomId, String mxid) {
    return room(roomId).joined.contains(mxid);
  }

  public int powerOf(String roomId, String mxid) {
    var room = room(roomId);
    return room.users.getOrDefault(mxid, room.usersDefault);
  }

  /**
   * Whether a message from this user would be accepted: joined and power {@code >= events_default}.
   */
  public boolean canPost(String roomId, String mxid) {
    var room = room(roomId);
    return room.joined.contains(mxid) && powerOf(roomId, mxid) >= room.eventsDefault;
  }

  /**
   * The admin join endpoint invites the target and then answers 500 before the join step ran, like
   * a Synapse that dies or times out between its two membership events.
   */
  public FakeSynapseHomeserver adminJoinFailsAfterInvite() {
    adminJoinFailsAfterInvite = true;
    return this;
  }

  /** Every membership leave (own or kick) is answered with 500. */
  public FakeSynapseHomeserver leavesAreRefused() {
    leavesAreRefused = true;
    return this;
  }

  /** Accounts the admin logged in as, in order (Synapse admin login-as-user). */
  public List<String> impersonatedAccounts() {
    return List.copyOf(impersonated);
  }

  public boolean isInvited(String roomId, String mxid) {
    return room(roomId).invited.contains(mxid);
  }

  /** How often the admin force-join endpoint was called. */
  public int adminJoinRequests() {
    return adminJoinRequests;
  }

  /** How often the make_room_admin endpoint was called. */
  public int makeRoomAdminRequests() {
    return makeRoomAdminRequests;
  }

  public Set<String> members(String roomId) {
    return Set.copyOf(room(roomId).joined);
  }

  // ── RestTemplate surface used by the gateway ──────────────────────────────

  @Override
  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> postForEntity(
      String url, Object request, Class<T> responseType, Object... uriVariables) {
    return (ResponseEntity<T>) handle(HttpMethod.POST, URI.create(url), request, responseType);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> postForEntity(URI url, Object request, Class<T> responseType) {
    return (ResponseEntity<T>) handle(HttpMethod.POST, url, request, responseType);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ResponseEntity<T> exchange(
      URI url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
    return (ResponseEntity<T>) handle(method, url, requestEntity, responseType);
  }

  @Override
  public void put(URI url, Object request) {
    handle(HttpMethod.PUT, url, request, Map.class);
  }

  // ── the homeserver ────────────────────────────────────────────────────────

  private ResponseEntity<?> handle(
      HttpMethod method, URI url, Object request, Class<?> responseType) {
    List<String> path = segments(url);
    HttpEntity<?> entity = request instanceof HttpEntity<?> e ? e : new HttpEntity<>(request);
    Object body = entity.getBody();
    String caller = callerOf(entity);

    if (method == HttpMethod.POST && path.equals(List.of("_matrix", "client", "r0", "login"))) {
      return login((MatrixLoginRequestDTO) body);
    }
    if (path.size() == 6
        && path.get(0).equals("_synapse")
        && path.get(3).equals("users")
        && path.get(5).equals("login")) {
      requireAdmin(caller);
      String target = path.get(4);
      if (!accounts.contains(target)) {
        throw error(HttpStatus.NOT_FOUND, "M_NOT_FOUND", "User not found");
      }
      impersonated.add(target);
      return ResponseEntity.ok(Map.of("access_token", tokenOf(target)));
    }
    if (path.size() >= 4 && path.get(0).equals("_synapse")) {
      return admin(method, path, body, caller);
    }
    if (path.size() >= 6 && path.get(1).equals("client") && path.get(3).equals("rooms")) {
      return clientRoom(method, path, body, caller, responseType);
    }
    throw new IllegalStateException("FakeSynapseHomeserver has no route for " + method + " " + url);
  }

  private ResponseEntity<?> login(MatrixLoginRequestDTO login) {
    String user = login.getUser();
    String mxid = user.startsWith("@") ? user : "@" + user + ":" + SERVER_NAME;
    if (!mxid.equals(adminMxid()) || !ADMIN_PASSWORD.equals(login.getPassword())) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "Invalid username or password");
    }
    return ResponseEntity.ok(Map.of("access_token", tokenOf(mxid)));
  }

  private ResponseEntity<?> admin(
      HttpMethod method, List<String> path, Object body, String caller) {
    requireAdmin(caller);
    // /_synapse/admin/v1/rooms/{room}/members
    if (method == HttpMethod.GET && path.get(3).equals("rooms") && path.get(5).equals("members")) {
      var members = new ArrayList<>(room(path.get(4)).joined);
      return ResponseEntity.ok(Map.of("members", members, "total", members.size()));
    }
    // /_synapse/admin/v1/join/{room}
    if (method == HttpMethod.POST && path.get(3).equals("join")) {
      adminJoinRequests++;
      return forceJoin(path.get(4), (String) asMap(body).get("user_id"), caller);
    }
    // /_synapse/admin/v1/rooms/{room}/make_room_admin
    if (method == HttpMethod.POST
        && path.get(3).equals("rooms")
        && path.get(5).equals("make_room_admin")) {
      makeRoomAdminRequests++;
      return makeRoomAdmin(path.get(4), (String) asMap(body).get("user_id"));
    }
    throw new IllegalStateException("FakeSynapseHomeserver has no admin route for " + path);
  }

  private ResponseEntity<?> forceJoin(String roomId, String target, String admin) {
    var room = room(roomId);
    if (!accounts.contains(target)) {
      throw error(HttpStatus.NOT_FOUND, "M_NOT_FOUND", "User not found");
    }
    // Synapse sends an invite as the calling admin for every non-public room.
    requireMayInvite(room, admin);
    if (room.joined.contains(target)) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "User is already in the room.");
    }
    if (adminJoinFailsAfterInvite) {
      room.invited.add(target);
      throw HttpServerErrorException.create(
          HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, new byte[0], null);
    }
    join(room, target);
    return ResponseEntity.ok(Map.of("room_id", roomId));
  }

  private ResponseEntity<?> makeRoomAdmin(String roomId, String target) {
    var room = room(roomId);
    String actor =
        room.users.entrySet().stream()
            .filter(entry -> room.joined.contains(entry.getKey()))
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow(
                () -> error(HttpStatus.BAD_REQUEST, "M_UNKNOWN", "No local admin user in room"));
    int actorPower = room.users.get(actor);
    if (actorPower < POWER_LEVELS_EVENT_LEVEL) {
      throw error(
          HttpStatus.BAD_REQUEST,
          "M_UNKNOWN",
          "No local admin user in room with power to update power levels.");
    }
    room.users.put(target, actorPower);
    if (!room.joined.contains(target) && !room.invited.contains(target)) {
      room.invited.add(target);
    }
    return ResponseEntity.ok(Map.of());
  }

  private ResponseEntity<?> clientRoom(
      HttpMethod method, List<String> path, Object body, String caller, Class<?> responseType) {
    String roomId = path.get(4);
    String action = path.get(5);
    var room = room(roomId);
    if (caller == null) {
      throw error(HttpStatus.UNAUTHORIZED, "M_MISSING_TOKEN", "Missing access token");
    }

    if (method == HttpMethod.POST && action.equals("invite")) {
      String target = ((MatrixInviteUserRequestDTO) body).getUserId();
      requireMayInvite(room, caller);
      if (room.joined.contains(target)) {
        throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", target + " is already in the room.");
      }
      room.invited.add(target);
      return ResponseEntity.ok(
          responseType == MatrixInviteUserResponseDTO.class
              ? new MatrixInviteUserResponseDTO()
              : Map.of());
    }
    if (method == HttpMethod.POST && action.equals("join")) {
      if (!room.joined.contains(caller) && !room.invited.contains(caller)) {
        throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "You are not invited to this room.");
      }
      join(room, caller);
      return ResponseEntity.ok(Map.of("room_id", roomId));
    }
    if (action.equals("state") && path.get(6).equals("m.room.power_levels")) {
      requireJoined(room, caller);
      if (method == HttpMethod.GET) {
        return ResponseEntity.ok(powerLevelsContent(room));
      }
      putPowerLevels(room, caller, asMap(body));
      return ResponseEntity.ok(Map.of());
    }
    if (method == HttpMethod.PUT && action.equals("state") && path.get(6).equals("m.room.member")) {
      String target = path.get(7);
      if (!"leave".equals(asMap(body).get("membership"))) {
        throw new IllegalStateException("FakeSynapseHomeserver only models membership=leave");
      }
      leaveOrKick(room, caller, target);
      return ResponseEntity.ok(Map.of());
    }
    throw new IllegalStateException("FakeSynapseHomeserver has no room route for " + path);
  }

  private void putPowerLevels(Room room, String sender, Map<String, Object> content) {
    int senderPower = room.users.getOrDefault(sender, room.usersDefault);
    if (senderPower < POWER_LEVELS_EVENT_LEVEL) {
      throw error(
          HttpStatus.FORBIDDEN, "M_FORBIDDEN", "You don't have permission to change power levels");
    }
    Map<String, Integer> next = new HashMap<>();
    asMap(content.get("users"))
        .forEach((user, level) -> next.put(user, ((Number) level).intValue()));
    Set<String> touched = new LinkedHashSet<>(next.keySet());
    touched.addAll(room.users.keySet());
    for (String user : touched) {
      Integer before = room.users.get(user);
      Integer after = next.get(user);
      if (java.util.Objects.equals(before, after)) {
        continue;
      }
      if (after != null && after > senderPower) {
        throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "Cannot set a level above your own");
      }
      if (before != null && before >= senderPower && !user.equals(sender)) {
        throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "Cannot change a level at or above yours");
      }
    }
    room.users.clear();
    room.users.putAll(next);
  }

  private void leaveOrKick(Room room, String sender, String target) {
    if (leavesAreRefused) {
      throw error(HttpStatus.INTERNAL_SERVER_ERROR, "M_UNKNOWN", "Internal server error");
    }
    if (sender.equals(target)) {
      room.joined.remove(target);
      room.invited.remove(target);
      room.left.add(target);
      return;
    }
    requireJoined(room, sender);
    int senderPower = room.users.getOrDefault(sender, room.usersDefault);
    int targetPower = room.users.getOrDefault(target, room.usersDefault);
    if (senderPower < KICK_LEVEL || senderPower <= targetPower) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "You cannot kick this user");
    }
    room.joined.remove(target);
    room.invited.remove(target);
    room.left.add(target);
  }

  private Map<String, Object> powerLevelsContent(Room room) {
    var content = new HashMap<String, Object>();
    content.put("users", new HashMap<String, Object>(room.users));
    content.put("users_default", room.usersDefault);
    content.put("events_default", room.eventsDefault);
    content.put("state_default", 50);
    content.put("invite", INVITE_LEVEL);
    content.put("kick", KICK_LEVEL);
    content.put("ban", KICK_LEVEL);
    content.put("redact", 50);
    content.put("events", new HashMap<String, Object>(Map.of("m.room.power_levels", 100)));
    return content;
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void requireMayInvite(Room room, String inviter) {
    requireJoined(room, inviter);
    if (room.users.getOrDefault(inviter, room.usersDefault) < INVITE_LEVEL) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "You don't have permission to invite");
    }
  }

  private void requireJoined(Room room, String user) {
    if (!room.joined.contains(user)) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "User is not in the room");
    }
  }

  private void requireAdmin(String caller) {
    if (!adminMxid().equals(caller)) {
      throw error(HttpStatus.FORBIDDEN, "M_FORBIDDEN", "You are not a server admin");
    }
  }

  private static void join(Room room, String user) {
    room.invited.remove(user);
    room.left.remove(user);
    room.joined.add(user);
  }

  private Room room(String roomId) {
    var room = rooms.get(roomId);
    if (room == null) {
      throw error(HttpStatus.NOT_FOUND, "M_NOT_FOUND", "Unknown room");
    }
    return room;
  }

  /** Tokens are {@code token-for:<mxid>}: the fake resolves the caller without a session table. */
  private static String tokenOf(String mxid) {
    return "token-for:" + mxid;
  }

  private String callerOf(HttpEntity<?> entity) {
    String header = entity.getHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer token-for:")) {
      return null;
    }
    String mxid = header.substring("Bearer token-for:".length());
    return accounts.contains(mxid) ? mxid : null;
  }

  private static List<String> segments(URI url) {
    var result = new ArrayList<String>();
    for (String segment : url.getRawPath().split("/")) {
      if (!segment.isEmpty()) {
        result.add(UriUtils.decode(segment, StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object body) {
    return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static HttpClientErrorException error(HttpStatus status, String errcode, String message) {
    return HttpClientErrorException.create(
        status,
        status.getReasonPhrase(),
        null,
        ("{\"errcode\":\"" + errcode + "\",\"error\":\"" + message + "\"}")
            .getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }
}
