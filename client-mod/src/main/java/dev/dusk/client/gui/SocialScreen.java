package dev.dusk.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CapeRegistry;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.social.Heads;
import dev.dusk.client.social.Social;
import dev.dusk.client.social.SocialNotifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Friends and chat in game: the launcher's SOCIAL page over the same service
 * calls, so a conversation is one conversation in both (whichever side reads
 * it marks it read). Friends down the left, the open conversation on the
 * right with its own bar (CHAT, PROFILE, and send a screenshot, an invite to
 * the server you're on or a gift), requests on their own tab.
 */
public class SocialScreen extends PanelScreen {
    private static final int FRIENDS = 0, REQUESTS = 1;
    private static final long LIST_POLL_MS = 10_000, CHAT_POLL_MS = 3_500, NOTICE_MS = 4_000, CONFIRM_MS = 4_000;
    /** Seconds: a time stamp after this long a gap, a new sender header after this long, gifts folded within this. */
    private static final long STAMP_GAP = 600, RUN_GAP = 300, GIFT_RUN_GAP = 600;
    private static final int GIFT_PREVIEW = 6, FRIEND_H = 26, SHOTS_MAX = 24, THUMB_W = 200, THUMB_H = 112;
    private static final int BTN_H = 18;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private enum View { CHAT, PROFILE, SHOTS }

    private interface Draw { void draw(Canvas c, int x, int y, int w, int mx, int my); }

    private interface Click { boolean click(double mx, double my, int x, int y, int w); }

    /** One line of the scrolling area, rebuilt every frame from the state. */
    private record Row(int h, Draw draw, @Nullable Click click) {}

    private record Btn(String label, Theme.Family family, boolean enabled, Runnable run) {}

    private interface Job { void run() throws Exception; }

    /** Kept across opens: the page comes back where you left it. */
    private static int tab = FRIENDS;
    @Nullable private static String selected;

    private final Minecraft mc = Minecraft.getInstance();
    private final ExecutorService io = single("duskclient-social-io"), worker = single("duskclient-social");
    private final RemoteImages heads = new RemoteImages(0), pics = new RemoteImages(1024), thumbs = new RemoteImages(320);
    private final NavBar sub = new NavBar();

    private View view = View.CHAT;
    private List<Social.Friend> friends = List.of();
    private Social.Requests reqs = new Social.Requests(List.of(), List.of());
    private boolean listLoaded, listBusy;
    @Nullable private String listError;
    private long nextList;

    private final List<Social.Message> messages = new ArrayList<>();
    private final Set<Long> messageIds = new HashSet<>();
    private long lastId, nextChat;
    private int gen;
    private boolean chatLoaded, chatBusy, pinBottom = true;
    @Nullable private String chatError;

    @Nullable private Social.Profile profile;
    @Nullable private String profileError;
    @Nullable private List<Path> shots;
    @Nullable private Path sending;

    @Nullable private String notice;
    private boolean noticeError;
    private long noticeAt;
    @Nullable private String confirm;
    private long confirmAt;
    /** The chat image open full screen, by its key in {@link #pics}. */
    @Nullable private String zoom;

    private String draft = "", addName = "";
    private final TextFieldWidget composer = new TextFieldWidget(() -> draft, s -> draft = s, true, Social.MESSAGE_MAX).themed();
    private final TextFieldWidget addField = new TextFieldWidget(() -> addName, s -> addName = s, true, 16)
            .themed().placeholder("MINECRAFT USERNAME");

    private int colX, colY, colW, colBottom, fscroll;
    private int rx, ry, rw, rh, headY;
    private int sendX, sendY, sendW;
    private List<Row> rows = List.of();

    public SocialScreen(@Nullable Screen parent) {
        super(Component.literal("Friends"), parent);
    }

    public static void show(@Nullable Screen parent) {
        Compat.setScreen(Minecraft.getInstance(), new SocialScreen(parent));
    }

    private static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    // ---- chrome ---------------------------------------------------------------

    @Override
    protected String[] tabs() {
        int n = reqs.incoming().size();
        return new String[] {"FRIENDS", n == 0 ? "REQUESTS" : "REQUESTS (" + n + ")"};
    }

    @Override
    protected int activeTab() {
        return tab;
    }

    @Override
    protected void selectTab(int i) {
        tab = i;
        scroll = 0;
        confirm = null;
        pinBottom = i == FRIENDS && view == View.CHAT;
    }

    @Override
    protected List<Tool> tools() {
        boolean alerts = DuskConfig.get().friendToasts;
        return List.of(new Tool("close", Icons.CLOSE, "", "Close"),
                new Tool("add", null, "+ ADD", "Send a friend request"),
                new Tool("alerts", null, alerts ? "ALERTS ON" : "ALERTS OFF",
                        "Pop-ups in game when friends message you, come online or send a request"));
    }

    @Override
    protected void onTool(String id) {
        switch (id) {
            case "add" -> {
                selectTab(REQUESTS);
                addField.setFocused(true);
            }
            case "alerts" -> {
                DuskConfig.get().friendToasts = !DuskConfig.get().friendToasts;
                DuskConfig.save();
            }
            default -> super.onTool(id);
        }
    }

    @Override
    protected int contentHeight() {
        int h = 0;
        for (Row r : rows) h += r.h;
        return h;
    }

    // ---- layout -----------------------------------------------------------------

    @Override
    protected void relayout() {
        layoutPanel();
        int top = bodyY() + pad, bottom = py + ph - pad;
        if (tab == FRIENDS) {
            colX = px + pad;
            colY = top;
            colW = Math.max(110, Math.min(170, Math.round(pw * 0.3f)));
            colBottom = bottom;
            rx = colX + colW + pad;
            ry = top;
            rw = px + pw - pad - rx;
            rh = bottom - top;
            List<NavBar.Tool> tools = List.of(
                    new NavBar.Tool("shot", Icons.MEDIA, "", "Send a screenshot"),
                    new NavBar.Tool("invite", Icons.ARROW, "", "Invite them to the server you're on"),
                    new NavBar.Tool("gift", Icons.GIFT, "", "Send a gift"));
            sub.layout(rx, ry, rw, new String[] {"CHAT", "PROFILE"}, tools, null, 0, this.font::width);
            headY = ry + NavBar.H;
            int listEnd = ry + rh - 6;
            if (view == View.CHAT && friend() != null) {
                sendW = 50;
                sendY = listEnd - BTN_H;
                sendX = rx + rw - 6 - sendW;
                composer.setBounds(rx + 6, sendY, sendX - 4 - (rx + 6), BTN_H);
                listEnd = sendY - 6;
            } else {
                composer.setBounds(0, 0, 0, 0);
            }
            addField.setBounds(0, 0, 0, 0);
            listX = rx + 6;
            listY = headY + 30;
            listW = rw - 12 - BAR_W - 3;
            listBottom = listEnd;
        } else {
            sendW = this.font.width("SEND REQUEST") + 16;
            sendY = top + 12;
            addField.setBounds(px + pad, sendY, Math.min(200, pw - 2 * pad - sendW - 4), BTN_H);
            sendX = addField.x + addField.w + 4;
            composer.setBounds(0, 0, 0, 0);
            listX = px + pad;
            listY = sendY + BTN_H + 20;
            listW = pw - 2 * pad - BAR_W - 3;
            listBottom = bottom;
        }
        rows = build();
        if (pinBottom && tab == FRIENDS && view == View.CHAT) scroll = maxScroll();
        else clampScroll();
        fscroll = Math.max(0, Math.min(fscrollMax(), fscroll));
    }

    private int fscrollMax() {
        return Math.max(0, friends.size() * FRIEND_H - (colBottom - colY));
    }

    @Nullable
    private Social.Friend friend() {
        if (selected == null) return null;
        for (Social.Friend f : friends) if (Social.same(f.uuid(), selected)) return f;
        return null;
    }

    // ---- polling and actions ------------------------------------------------------

    private void loadList() {
        listBusy = true;
        nextList = System.currentTimeMillis() + LIST_POLL_MS;
        io.execute(() -> {
            try {
                List<Social.Friend> f = Social.friends();
                Social.Requests r = Social.requests();
                mc.execute(() -> {
                    listBusy = false;
                    listLoaded = true;
                    listError = null;
                    reqs = r;
                    setFriends(f);
                });
            } catch (Exception e) {
                mc.execute(() -> {
                    listBusy = false;
                    if (!listLoaded) listError = message(e);
                });
            }
        });
    }

    private void setFriends(List<Social.Friend> list) {
        List<Social.Friend> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing((Social.Friend f) -> !f.online()).thenComparing(f -> f.username().toLowerCase(Locale.ROOT)));
        friends = sorted;
        SocialNotifier.seen(friends, reqs.incoming().size());
        Social.Friend open = friend();
        if (open == null && selected != null) selected = null;
        if (selected == null && !friends.isEmpty()) {
            Social.Friend pick = friends.stream().filter(f -> f.unread() > 0).findFirst().orElse(friends.get(0));
            select(pick.uuid());
        } else if (open != null && open.unread() > 0) {
            nextChat = 0; // something arrived since the last chat poll
        }
    }

    private void select(String uuid) {
        selected = uuid;
        view = View.CHAT;
        profile = null;
        profileError = null;
        confirm = null;
        scroll = 0;
        pinBottom = true;
        draft = "";
        composer.setText("");
        gen++;
        messages.clear();
        messageIds.clear();
        lastId = 0;
        chatLoaded = false;
        chatBusy = false;
        chatError = null;
        nextChat = 0;
    }

    private void pollChat() {
        String uuid = selected;
        if (uuid == null) return;
        int g = gen;
        long after = lastId;
        chatBusy = true;
        nextChat = System.currentTimeMillis() + CHAT_POLL_MS;
        io.execute(() -> {
            try {
                List<Social.Message> list = Social.messages(uuid, after);
                mc.execute(() -> {
                    if (g != gen) return;
                    chatBusy = false;
                    chatLoaded = true;
                    chatError = null;
                    addMessages(list);
                    markRead(uuid);
                });
            } catch (Exception e) {
                mc.execute(() -> {
                    if (g != gen) return;
                    chatBusy = false;
                    if (!chatLoaded) chatError = message(e);
                });
            }
        });
    }

    private void addMessages(List<Social.Message> list) {
        boolean added = false;
        for (Social.Message m : list) {
            if (!messageIds.add(m.id())) continue;
            messages.add(m);
            lastId = Math.max(lastId, m.id());
            added = true;
        }
        if (added) messages.sort(Comparator.comparingLong(Social.Message::id));
    }

    /** Reading a conversation marks it read on the service; mirror that in the list and the badges. */
    private void markRead(String uuid) {
        List<Social.Friend> out = new ArrayList<>(friends.size());
        boolean changed = false;
        for (Social.Friend f : friends) {
            if (Social.same(f.uuid(), uuid) && f.unread() > 0) {
                out.add(new Social.Friend(f.uuid(), f.username(), f.online(), f.playing(), f.server(), f.lastSeen(), 0));
                changed = true;
            } else {
                out.add(f);
            }
        }
        if (!changed) return;
        friends = out;
        SocialNotifier.seen(friends, reqs.incoming().size());
    }

    private void act(@Nullable String doing, Job job, @Nullable Runnable done) {
        if (doing != null) note(doing, false);
        worker.execute(() -> {
            try {
                job.run();
                mc.execute(() -> {
                    if (doing != null && doing.equals(notice)) notice = null;
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                mc.execute(() -> note(message(e), true));
            }
        });
    }

    private void note(String text, boolean error) {
        notice = text;
        noticeError = error;
        noticeAt = System.currentTimeMillis();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "Couldn't reach the Dusk service." : m;
    }

    /** Destructive buttons take a second click within a few seconds. */
    private boolean confirmed(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(confirm) && now - confirmAt < CONFIRM_MS) {
            confirm = null;
            return true;
        }
        confirm = key;
        confirmAt = now;
        return false;
    }

    private boolean armed(String key) {
        return key.equals(confirm) && System.currentTimeMillis() - confirmAt < CONFIRM_MS;
    }

    private void openView(View v) {
        view = v;
        scroll = 0;
        confirm = null;
        pinBottom = v == View.CHAT;
        String uuid = selected;
        if (uuid == null) return;
        if (v == View.PROFILE) {
            profile = null;
            profileError = null;
            io.execute(() -> {
                try {
                    Social.Profile p = Social.profile(uuid);
                    mc.execute(() -> {
                        if (Objects.equals(selected, uuid)) profile = p;
                    });
                } catch (Exception e) {
                    mc.execute(() -> profileError = message(e));
                }
            });
        } else if (v == View.SHOTS) {
            shots = null;
            io.execute(() -> {
                List<Path> list = Social.screenshots(SHOTS_MAX);
                mc.execute(() -> shots = list);
            });
        }
    }

    private void send() {
        String body = draft.trim(), uuid = selected;
        if (body.isEmpty() || uuid == null) return;
        composer.setText("");
        pinBottom = true;
        int g = gen;
        worker.execute(() -> {
            try {
                Social.Message m = Social.send(uuid, body);
                mc.execute(() -> {
                    if (g == gen) addMessages(List.of(m));
                });
            } catch (Exception e) {
                mc.execute(() -> {
                    if (g == gen && draft.isEmpty()) composer.setText(body);
                    note(message(e), true);
                });
            }
        });
    }

    private void sendShot(Path file) {
        String uuid = selected;
        if (uuid == null || sending != null) return;
        sending = file;
        int g = gen;
        worker.execute(() -> {
            try {
                Social.Message m = Social.screenshot(uuid, file);
                mc.execute(() -> {
                    sending = null;
                    if (g != gen) return;
                    addMessages(List.of(m));
                    openView(View.CHAT);
                });
            } catch (Exception e) {
                mc.execute(() -> {
                    sending = null;
                    note(message(e), true);
                });
            }
        });
    }

    private void invite() {
        String uuid = selected;
        if (uuid == null) return;
        ServerData sd = mc.getCurrentServer();
        if (sd == null || mc.isLocalServer()) {
            note("Join a server in game first — then INVITE sends them its address.", true);
            return;
        }
        String ip = sd.ip;
        int g = gen;
        act("Sending invite...", () -> {
            Social.Message m = Social.invite(uuid, ip, mcVersion());
            mc.execute(() -> {
                if (g == gen) addMessages(List.of(m));
            });
        }, () -> openView(View.CHAT));
    }

    @Nullable
    private static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    private void join(String ip) {
        ServerData here = mc.getCurrentServer();
        if (here != null && here.ip.equalsIgnoreCase(ip)) {
            note("You're already on " + ip + ".", false);
            return;
        }
        if (inWorld() && !confirmed("join:" + ip)) {
            note("Click again to leave this world and join " + ip + ".", false);
            return;
        }
        Screen back = new JoinMultiplayerScreen(new DuskTitleScreen());
        if (inWorld()) Compat.leaveWorld(mc);
        ConnectScreen.startConnecting(back, mc, ServerAddress.parseString(ip), new ServerData(ip, ip, ServerData.Type.OTHER), false, null);
    }

    private void sendRequest() {
        String name = addName.trim();
        if (name.isEmpty()) return;
        act("Sending request...", () -> {
            Social.sendRequest(name);
            Social.Requests r = Social.requests();
            mc.execute(() -> reqs = r);
        }, () -> {
            addField.setText("");
            note("Request sent to " + name + ".", false);
        });
    }

    private void answer(Social.Request r, boolean accept) {
        act(accept ? "Accepting..." : null, () -> {
            Social.Requests now = accept ? Social.accept(r.id()) : Social.decline(r.id());
            mc.execute(() -> {
                reqs = now;
                SocialNotifier.seen(friends, now.incoming().size());
            });
        }, () -> {
            if (accept) note("You and " + r.username() + " are friends now.", false);
            nextList = 0;
        });
    }

    private void remove(Social.Friend f, boolean block) {
        if (!confirmed(block ? "block" : "remove")) return;
        act(block ? "Blocking..." : "Removing...", () -> {
            if (block) Social.block(f.uuid());
            else Social.remove(f.uuid());
        }, () -> {
            if (Objects.equals(selected, f.uuid())) selected = null;
            friends = friends.stream().filter(x -> !Social.same(x.uuid(), f.uuid())).toList();
            view = View.CHAT;
            nextList = 0;
        });
    }

    /** The store, buying for this friend; it stays open for more than one gift. */
    private void giftStore() {
        Social.Friend f = friend();
        if (f != null) WardrobeScreen.gift(this, f.uuid(), f.username());
    }

    // ---- rows -------------------------------------------------------------------

    private List<Row> build() {
        List<Row> out = new ArrayList<>();
        if (tab == REQUESTS) {
            buildRequests(out);
            return out;
        }
        Social.Friend f = friend();
        if (f == null) return out;
        switch (view) {
            case CHAT -> buildChat(out, f);
            case PROFILE -> buildProfile(out, f);
            case SHOTS -> buildShots(out, f);
        }
        return out;
    }

    private void buildRequests(List<Row> out) {
        out.add(section("RECEIVED"));
        if (reqs.incoming().isEmpty()) out.add(text("NO PENDING REQUESTS", Theme.TEXT_FAINT, false));
        for (Social.Request r : reqs.incoming()) {
            out.add(row(28, person(r.uuid(), r.username(), "sent " + ago(r.createdAt())), true,
                    new Btn("ACCEPT", Theme.Family.GREEN, true, () -> answer(r, true)),
                    new Btn("DECLINE", Theme.Family.GREY, true, () -> answer(r, false))));
        }
        out.add(gap(10));
        out.add(section("SENT"));
        if (reqs.outgoing().isEmpty()) out.add(text("Nothing waiting on anyone.", Theme.TEXT_FAINT, false));
        for (Social.Request r : reqs.outgoing()) {
            out.add(row(28, person(r.uuid(), r.username(), "sent " + ago(r.createdAt())), true,
                    new Btn("CANCEL", Theme.Family.GREY, true, () -> answer(r, false))));
        }
    }

    private void buildChat(List<Row> out, Social.Friend f) {
        if (!chatLoaded) {
            out.add(text(chatError != null ? chatError : "Loading...", chatError != null ? Theme.RED_UP : Theme.TEXT_MUTED, true));
            return;
        }
        if (messages.isEmpty()) {
            for (String line : wrapF("Say hello — this is the start of your conversation with " + f.username() + ".", listW - 20)) {
                out.add(text(line, Theme.TEXT_MUTED, true));
            }
            return;
        }
        String me = mc.getUser().getName();
        int maxW = Math.round(listW * 0.72f);
        for (int i = 0; i < messages.size(); i++) {
            Social.Message m = messages.get(i), prev = i > 0 ? messages.get(i - 1) : null;
            boolean newDay = prev == null || !day(prev.sentAt()).equals(day(m.sentAt()));
            boolean stamp = newDay || m.sentAt() - prev.sentAt() > STAMP_GAP;
            if (stamp) out.add(stamp(m.sentAt(), newDay));
            boolean mine = !Social.same(m.from(), f.uuid());
            boolean run = !stamp && Social.same(prev.from(), m.from()) && m.sentAt() - prev.sentAt() <= RUN_GAP;
            if (!run) out.add(header(mine ? me : f.username(), m.sentAt(), mine));
            switch (m.kind()) {
                case "gift" -> {
                    int j = i;
                    while (j + 1 < messages.size()) {
                        Social.Message next = messages.get(j + 1);
                        if (!next.kind().equals("gift") || !Social.same(next.from(), m.from())
                                || next.sentAt() - messages.get(j).sentAt() > GIFT_RUN_GAP) break;
                        j++;
                    }
                    if (j > i) {
                        out.add(giftRun(messages.subList(i, j + 1), mine, f.username(), maxW));
                        i = j;
                    } else {
                        out.add(gift(m, mine, f.username(), maxW));
                    }
                }
                case "invite" -> out.add(invite(m, mine, f.username(), maxW));
                case "image" -> out.add(image(m, mine));
                default -> out.add(bubble(m.body(), mine, maxW));
            }
            out.add(gap(3));
        }
    }

    private void buildProfile(List<Row> out, Social.Friend f) {
        out.add(new Row(44, (c, x, y, w, mx, my) -> {
            Heads.draw(c, heads, f.uuid(), x + 2, y + 4, 32);
            c.text(Theme.ellipsize(c, f.username(), w - 44), x + 42, y + 10, Theme.TEXT, false);
            c.text(Theme.ellipsize(c, status(f), w - 44), x + 42, y + 22, f.online() ? Theme.GREEN_LO : Theme.TEXT_FAINT, false);
        }, null));
        Social.Profile p = profile;
        if (p == null) {
            out.add(text(profileError != null ? profileError : "Loading...", profileError != null ? Theme.RED_UP : Theme.TEXT_MUTED, false));
        } else {
            CapeRegistry.CapeEntry cape = CapeRegistry.cape(p.cape());
            out.add(section("CAPE"));
            out.add(text(cape != null ? cape.name() : p.cape() >= 0 ? "Cape #" + p.cape() : "None", Theme.TEXT, false));
            out.add(gap(6));
            out.add(section("ACCESSORIES"));
            if (p.accessories().isEmpty()) out.add(text("None", Theme.TEXT, false));
            for (int id : p.accessories()) {
                CapeRegistry.AccessoryEntry a = CapeRegistry.accessory(id);
                out.add(text(a != null ? a.name() : "Accessory #" + id, Theme.TEXT, false));
            }
            if (!p.badges().isEmpty()) {
                out.add(gap(6));
                out.add(section("BADGES"));
                for (String b : p.badges()) out.add(text(b, Theme.ACCENT, false));
            }
        }
        out.add(gap(10));
        List<Btn> btns = new ArrayList<>();
        btns.add(new Btn("MESSAGE", Theme.Family.ACCENT, true, () -> openView(View.CHAT)));
        if (f.online() && f.server() != null) {
            String ip = f.server();
            btns.add(new Btn(armed("join:" + ip) ? "SURE?" : "JOIN", Theme.Family.GREEN, true, () -> join(ip)));
        }
        btns.add(new Btn("GIFT", Theme.Family.SOFT, true, this::giftStore));
        btns.add(new Btn(armed("remove") ? "SURE?" : "REMOVE", Theme.Family.GREY, true, () -> remove(f, false)));
        btns.add(new Btn(armed("block") ? "SURE?" : "BLOCK", Theme.Family.GREY, true, () -> remove(f, true)));
        out.add(row(BTN_H + 4, null, false, btns.toArray(Btn[]::new)));
    }

    private void buildShots(List<Row> out, Social.Friend f) {
        out.add(section("SEND A SCREENSHOT TO " + f.username().toUpperCase(Locale.ROOT)));
        List<Path> list = shots;
        if (list == null) {
            out.add(text("Loading...", Theme.TEXT_MUTED, false));
            return;
        }
        if (list.isEmpty()) {
            out.add(text("No screenshots yet — press F2 in game.", Theme.TEXT_MUTED, false));
            return;
        }
        out.add(text("Your newest " + list.size() + ". Click one to send it.", Theme.TEXT_FAINT, false));
        out.add(gap(4));
        int cols = Math.max(1, (listW + 4) / 104), tw = (listW - (cols - 1) * 4) / cols, th = tw * 9 / 16;
        for (int start = 0; start < list.size(); start += cols) {
            List<Path> line = list.subList(start, Math.min(list.size(), start + cols));
            out.add(new Row(th + 4, (c, x, y, w, mx, my) -> {
                for (int k = 0; k < line.size(); k++) {
                    Path p = line.get(k);
                    int tx = x + k * (tw + 4);
                    boolean hot = sending == null && Vanilla.inside(mx, my, tx, y, tw, th);
                    c.fill(tx, y, tx + tw, y + th, 0xFF101010);
                    RemoteImages.Image img = thumbs.get(p.toString(), () -> Files.readAllBytes(p));
                    if (img != null) fit(c, img, tx, y, tw, th);
                    c.outline(tx, y, tw, th, hot ? Theme.LABEL_UP : 0xFF000000);
                    if (p.equals(sending)) {
                        c.fill(tx, y, tx + tw, y + th, 0xAA000000);
                        c.centeredText("SENDING...", tx + tw / 2, y + th / 2 - 4, Theme.TEXT, false);
                    }
                }
            }, (mx, my, x, y, w) -> {
                for (int k = 0; k < line.size(); k++) {
                    int tx = x + k * (tw + 4);
                    if (Vanilla.inside(mx, my, tx, y, tw, th)) {
                        sendShot(line.get(k));
                        return true;
                    }
                }
                return false;
            }));
        }
    }

    // ---- row kinds ------------------------------------------------------------

    private static Row gap(int h) {
        return new Row(h, (c, x, y, w, mx, my) -> {}, null);
    }

    private static Row section(String label) {
        return new Row(14, (c, x, y, w, mx, my) -> Theme.label(c, label, x + 2, y + 3, Theme.LABEL_UP, Theme.LABEL_LO, 1f), null);
    }

    private static Row text(String s, int color, boolean centred) {
        return new Row(12, (c, x, y, w, mx, my) -> {
            String t = Theme.ellipsize(c, s, w - 4);
            c.text(t, centred ? x + (w - c.textWidth(t)) / 2 : x + 2, y + 2, color, false);
        }, null);
    }

    /** A head, a name and a faint line under it. */
    private Draw person(String uuid, String name, String sub) {
        return (c, x, y, w, mx, my) -> {
            Heads.draw(c, heads, uuid, x + 4, y + 6, 16);
            c.text(Theme.ellipsize(c, name, w - 160), x + 26, y + 5, Theme.TEXT, false);
            c.text(Theme.ellipsize(c, sub, w - 160), x + 26, y + 16, Theme.TEXT_FAINT, false);
        };
    }

    /** {@code content}, plus buttons along the row (right-aligned or from the left). */
    private Row row(int h, @Nullable Draw content, boolean right, Btn... btns) {
        int[] widths = new int[btns.length];
        for (int i = 0; i < btns.length; i++) widths[i] = Math.max(44, this.font.width(btns[i].label) + 16);
        return new Row(h, (c, x, y, w, mx, my) -> {
            if (content != null) content.draw(c, x, y, w, mx, my);
            int[] xs = btnXs(x, w, right, widths);
            for (int i = 0; i < btns.length; i++) {
                Btn b = btns[i];
                boxButton(c, b.label, xs[i], y + (h - BTN_H) / 2, widths[i], BTN_H, mx, my, b.family, false, b.enabled);
            }
        }, (mx, my, x, y, w) -> {
            int[] xs = btnXs(x, w, right, widths);
            for (int i = 0; i < btns.length; i++) {
                if (btns[i].enabled && Vanilla.inside(mx, my, xs[i], y + (h - BTN_H) / 2, widths[i], BTN_H)) {
                    btns[i].run.run();
                    return true;
                }
            }
            return false;
        });
    }

    private static int[] btnXs(int x, int w, boolean right, int[] widths) {
        int[] xs = new int[widths.length];
        if (right) {
            int cx = x + w - 4;
            for (int i = widths.length - 1; i >= 0; i--) {
                cx -= widths[i];
                xs[i] = cx;
                cx -= 4;
            }
        } else {
            int cx = x + 2;
            for (int i = 0; i < widths.length; i++) {
                xs[i] = cx;
                cx += widths[i] + 4;
            }
        }
        return xs;
    }

    private static Row stamp(long t, boolean newDay) {
        String s = newDay ? dayLabel(t) + "  " + hm(t) : hm(t);
        return new Row(16, (c, x, y, w, mx, my) -> c.text(s, x + (w - c.textWidth(s)) / 2, y + 5, Theme.TEXT_FAINT, false), null);
    }

    private static Row header(String name, long t, boolean mine) {
        return new Row(12, (c, x, y, w, mx, my) -> {
            String time = hm(t);
            int nw = c.textWidth(name), tw = c.textWidth(time);
            int nx = mine ? x + w - nw - tw - 6 : x + 1;
            c.text(name, nx, y + 2, Theme.TEXT_MUTED, false);
            c.text(time, nx + nw + 5, y + 2, Theme.TEXT_FAINT, false);
        }, null);
    }

    private Row bubble(String body, boolean mine, int maxW) {
        List<String> lines = new ArrayList<>();
        for (String para : body.split("\n", -1)) lines.addAll(wrapF(para, maxW - 10));
        if (lines.isEmpty()) lines.add("");
        int bw = 0;
        for (String l : lines) bw = Math.max(bw, this.font.width(l));
        int boxW = bw + 10, boxH = lines.size() * 10 + 6;
        return new Row(boxH + 1, (c, x, y, w, mx, my) -> {
            int bx = mine ? x + w - boxW : x;
            Theme.box(c, bx, y, boxW, boxH, mine ? Theme.Family.SOFT : Theme.Family.GREY, false, false);
            for (int k = 0; k < lines.size(); k++) c.text(lines.get(k), bx + 5, y + 4 + k * 10, Theme.TEXT, false);
        }, null);
    }

    private Row invite(Social.Message m, boolean mine, String name, int maxW) {
        String server = m.meta("server"), version = m.meta("version");
        int cw = Math.min(maxW, 210), ch = 46;
        String title = mine ? "YOU SENT AN INVITE" : name.toUpperCase(Locale.ROOT) + " INVITED YOU";
        boolean canJoin = !mine && !server.isEmpty();
        return new Row(ch + 1, (c, x, y, w, mx, my) -> {
            int bx = mine ? x + w - cw : x;
            Theme.box(c, bx, y, cw, ch, Theme.Family.GREY, false, false);
            int textW = cw - (canJoin ? 60 : 12);
            Theme.label(c, Theme.ellipsize(c, title, textW), bx + 6, y + 6, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
            c.text(Theme.ellipsize(c, server.isEmpty() ? "A server" : server, textW), bx + 6, y + 19, Theme.TEXT, false);
            if (!version.isEmpty()) c.text("Minecraft " + version, bx + 6, y + 31, Theme.TEXT_FAINT, false);
            if (canJoin) {
                String label = armed("join:" + server) ? "SURE?" : "JOIN";
                boxButton(c, label, bx + cw - 52, y + (ch - BTN_H) / 2, 46, BTN_H, mx, my, Theme.Family.GREEN, false, true);
            }
        }, canJoin ? (mx, my, x, y, w) -> {
            int bx = mine ? x + w - cw : x;
            if (!Vanilla.inside(mx, my, bx + cw - 52, y + (ch - BTN_H) / 2, 46, BTN_H)) return false;
            join(server);
            return true;
        } : null);
    }

    private Row gift(Social.Message m, boolean mine, String name, int maxW) {
        String title = mine ? "YOU GIFTED " + name.toUpperCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT) + " GIFTED YOU";
        String item = m.meta("name").isEmpty() ? "A cosmetic" : m.meta("name");
        int cw = Math.min(maxW, 210), ch = mine ? 32 : 44;
        return new Row(ch + 1, (c, x, y, w, mx, my) -> {
            int bx = mine ? x + w - cw : x;
            Theme.box(c, bx, y, cw, ch, Theme.Family.SOFT, false, false);
            Icons.GIFT.draw(c, bx + 6, y + 6, Theme.ACCENT);
            Theme.label(c, Theme.ellipsize(c, title, cw - 30), bx + 18, y + 6, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
            c.text(Theme.ellipsize(c, item, cw - 12), bx + 6, y + 19, Theme.TEXT, false);
            if (!mine) c.text("Equip it in the wardrobe.", bx + 6, y + 31, Theme.TEXT_FAINT, false);
        }, null);
    }

    private Row giftRun(List<Social.Message> run, boolean mine, String name, int maxW) {
        String upper = name.toUpperCase(Locale.ROOT);
        String title = mine ? "YOU GIFTED " + upper + " " + run.size() + " ITEMS" : upper + " GIFTED YOU " + run.size() + " ITEMS";
        List<String> names = new ArrayList<>();
        for (int k = 0; k < Math.min(GIFT_PREVIEW, run.size()); k++) {
            String n = run.get(k).meta("name");
            names.add(n.isEmpty() ? "A cosmetic" : n);
        }
        if (run.size() > GIFT_PREVIEW) names.add("+" + (run.size() - GIFT_PREVIEW) + " more");
        int cw = Math.min(maxW, 210), ch = 18 + names.size() * 10 + 4;
        return new Row(ch + 1, (c, x, y, w, mx, my) -> {
            int bx = mine ? x + w - cw : x;
            Theme.box(c, bx, y, cw, ch, Theme.Family.SOFT, false, false);
            Icons.GIFT.draw(c, bx + 6, y + 6, Theme.ACCENT);
            Theme.label(c, Theme.ellipsize(c, title, cw - 30), bx + 18, y + 6, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
            for (int k = 0; k < names.size(); k++) {
                c.text(Theme.ellipsize(c, names.get(k), cw - 12), bx + 6, y + 18 + k * 10, k < GIFT_PREVIEW ? Theme.TEXT : Theme.TEXT_FAINT, false);
            }
        }, null);
    }

    private Row image(Social.Message m, boolean mine) {
        String id = m.meta("image"), key = "img:" + id;
        RemoteImages.Image img = id.isEmpty() ? null : pics.get(key, () -> Social.image(id));
        boolean failed = id.isEmpty() || pics.failed(key);
        int tw = THUMB_W, th = THUMB_H;
        if (img != null) {
            float k = Math.min(1f, Math.min(THUMB_W / (float) img.w(), THUMB_H / (float) img.h()));
            tw = Math.max(16, Math.round(img.w() * k));
            th = Math.max(16, Math.round(img.h() * k));
        } else if (failed) {
            tw = 120;
            th = 36;
        }
        int fw = tw, fh = th;
        return new Row(fh + 1, (c, x, y, w, mx, my) -> {
            int bx = mine ? x + w - fw : x;
            c.fill(bx, y, bx + fw, y + fh, 0xFF101010);
            if (img != null) {
                fit(c, img, bx, y, fw, fh);
                c.outline(bx, y, fw, fh, Vanilla.inside(mx, my, bx, y, fw, fh) ? Theme.LABEL_UP : 0xFF000000);
            } else {
                c.outline(bx, y, fw, fh, 0xFF000000);
                String s = failed ? "EXPIRED" : "Loading...";
                c.text(s, bx + (fw - c.textWidth(s)) / 2, y + fh / 2 - 4, Theme.TEXT_FAINT, false);
            }
        }, img == null ? null : (mx, my, x, y, w) -> {
            int bx = mine ? x + w - fw : x;
            if (!Vanilla.inside(mx, my, bx, y, fw, fh)) return false;
            zoom = key;
            return true;
        });
    }

    /** {@code img} scaled to fit the box, centred. */
    private static void fit(Canvas c, RemoteImages.Image img, int x, int y, int w, int h) {
        float k = Math.min(w / (float) img.w(), h / (float) img.h());
        int dw = Math.round(img.w() * k), dh = Math.round(img.h() * k);
        c.push();
        c.translate(x + (w - dw) / 2f, y + (h - dh) / 2f);
        c.scale(k, k);
        c.blit(img.id(), 0, 0, 0, 0, img.w(), img.h(), img.w(), img.h());
        c.pop();
    }

    // ---- drawing ------------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        if (!listBusy && now >= nextList) loadList();
        if (selected != null && tab == FRIENDS && !chatBusy && now >= nextChat) pollChat();
        if (notice != null && now - noticeAt > (noticeError ? NOTICE_MS + 2000 : NOTICE_MS)) notice = null;
        if (zoom != null) mouseX = mouseY = -1;
        relayout();
        drawPanel(c, mouseX, mouseY);
        if (tab == FRIENDS) drawFriends(c, mouseX, mouseY);
        else drawRequests(c, mouseX, mouseY);
        if (notice != null) {
            int nw = Math.min(listW, c.textWidth(notice) + 12), nx = listX + (listW - nw) / 2, ny = listBottom - 16;
            Theme.box(c, nx, ny, nw, 14, noticeError ? Theme.Family.GREY : Theme.Family.SOFT, false, false);
            c.text(Theme.ellipsize(c, notice, nw - 10), nx + 6, ny + 3, noticeError ? Theme.RED_UP : Theme.TEXT, false);
        }
        if (zoom != null) drawZoom(c);
    }

    private void drawFriends(Canvas c, int mx, int my) {
        if (!listLoaded || friends.isEmpty()) {
            int cy = bodyY() + (py + ph - bodyY()) / 2 - 12;
            if (!listLoaded) {
                String s = listError != null ? listError : "Loading friends...";
                c.text(s, px + (pw - c.textWidth(s)) / 2, cy, listError != null ? Theme.RED_UP : Theme.TEXT_MUTED, false);
                return;
            }
            String title = "NO FRIENDS YET", hint = "Add someone with + ADD. They need to have signed in to Dusk Launcher once.";
            Theme.label(c, title, px + (pw - c.textWidth(title)) / 2, cy, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
            String h = Theme.ellipsize(c, hint, pw - 2 * pad);
            c.text(h, px + (pw - c.textWidth(h)) / 2, cy + 14, Theme.TEXT_FAINT, false);
            return;
        }
        // the column
        c.fill(colX, colY, colX + colW, colBottom, 0x40000000);
        c.scissor(colX, colY, colX + colW, colBottom);
        boolean inCol = Vanilla.inside(mx, my, colX, colY, colW, colBottom - colY);
        for (int i = 0; i < friends.size(); i++) {
            int y = colY + i * FRIEND_H - fscroll;
            if (y + FRIEND_H < colY || y > colBottom) continue;
            Social.Friend f = friends.get(i);
            boolean sel = Social.same(f.uuid(), selected == null ? "" : selected);
            boolean hot = inCol && my >= y && my < y + FRIEND_H;
            if (sel) Theme.box(c, colX, y, colW, FRIEND_H - 1, Theme.Family.SOFT, false, false);
            else if (hot) c.fill(colX, y, colX + colW, y + FRIEND_H - 1, 0x22FFFFFF);
            Heads.draw(c, heads, f.uuid(), colX + 5, y + 5, 16);
            if (f.online()) {
                c.fill(colX + 17, y + 17, colX + 23, y + 23, 0xFF000000);
                c.fill(colX + 18, y + 18, colX + 22, y + 22, Theme.GREEN_LO);
            }
            int badgeW = 0;
            if (f.unread() > 0) {
                String n = f.unread() > 99 ? "99+" : String.valueOf(f.unread());
                badgeW = c.textWidth(n) + 6;
                int bx = colX + colW - badgeW - 5;
                c.fill(bx, y + 8, bx + badgeW, y + 18, Theme.ACCENT);
                c.text(n, bx + 3, y + 9, 0xFF1A1A1A, false);
            }
            int tw = colW - 28 - (badgeW > 0 ? badgeW + 8 : 4);
            c.text(Theme.ellipsize(c, f.username(), tw), colX + 27, y + 4, Theme.TEXT, false);
            c.text(Theme.ellipsize(c, status(f), tw), colX + 27, y + 14, f.online() ? Theme.GREEN_LO : Theme.TEXT_FAINT, false);
        }
        c.unscissor();
        int fm = fscrollMax();
        if (fm > 0) {
            int h = colBottom - colY, barH = Math.max(12, h * h / (friends.size() * FRIEND_H));
            int barY = colY + (h - barH) * fscroll / fm;
            c.fill(colX + colW - 2, barY, colX + colW, barY + barH, Theme.LABEL_LO);
        }

        // the conversation
        NavBar.window(c, rx, ry, rw, rh);
        Social.Friend f = friend();
        if (f == null) {
            String s = "Pick a friend to chat with.";
            c.text(s, rx + (rw - c.textWidth(s)) / 2, ry + rh / 2, Theme.TEXT_MUTED, false);
            return;
        }
        int active = view == View.CHAT ? 0 : view == View.PROFILE ? 1 : -1;
        sub.draw(c, active, mx, my, this.width, this.height);
        Heads.draw(c, heads, f.uuid(), rx + 7, headY + 5, 20);
        boolean canJoin = f.online() && f.server() != null;
        int joinX = rx + rw - 6 - 46, textW = rw - 40 - (canJoin ? 56 : 8);
        c.text(Theme.ellipsize(c, f.username(), textW), rx + 33, headY + 6, Theme.TEXT, false);
        c.text(Theme.ellipsize(c, status(f), textW), rx + 33, headY + 17, f.online() ? Theme.GREEN_LO : Theme.TEXT_FAINT, false);
        if (canJoin) {
            String label = armed("join:" + f.server()) ? "SURE?" : "JOIN";
            boxButton(c, label, joinX, headY + 6, 46, BTN_H, mx, my, Theme.Family.GREEN, false, true);
        }
        c.fill(rx + 1, headY + 29, rx + rw - 1, headY + 30, 0xFF000000);

        drawRows(c, mx, my);
        drawScrollbar(c);
        if (view == View.CHAT) {
            composer.placeholder("Message " + f.username());
            composer.render(c, mx, my);
            boxButton(c, "SEND", sendX, sendY, sendW, BTN_H, mx, my, Theme.Family.ACCENT, false, !draft.isBlank());
        }
    }

    private void drawRequests(Canvas c, int mx, int my) {
        Theme.label(c, "ADD A FRIEND", px + pad + 1, sendY - 11, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
        addField.render(c, mx, my);
        boxButton(c, "SEND REQUEST", sendX, sendY, sendW, BTN_H, mx, my, Theme.Family.ACCENT, false, !addName.isBlank());
        String hint = "Their Minecraft username. They need to have signed in to Dusk Launcher once.";
        c.text(Theme.ellipsize(c, hint, pw - 2 * pad), px + pad + 1, sendY + BTN_H + 5, Theme.TEXT_FAINT, false);
        drawRows(c, mx, my);
        drawScrollbar(c);
    }

    private void drawRows(Canvas c, int mx, int my) {
        boolean hot = inList(mx, my);
        int hx = hot ? mx : -1, hy = hot ? my : -1;
        c.scissor(listX, listY, listX + listW, listBottom);
        int y = listY - scroll;
        for (Row r : rows) {
            if (y + r.h >= listY && y <= listBottom) r.draw.draw(c, listX, y, listW, hx, hy);
            y += r.h;
        }
        c.unscissor();
    }

    private void drawZoom(Canvas c) {
        c.beginLayer();
        c.fill(0, 0, this.width, this.height, 0xDD000000);
        RemoteImages.Image img = pics.get(zoom, () -> null);
        if (img != null) fit(c, img, this.width / 20, this.height / 20, this.width * 9 / 10, this.height * 9 / 10 - 12);
        String s = "Click anywhere to close";
        c.text(s, (this.width - c.textWidth(s)) / 2, this.height - 14, Theme.TEXT_FAINT, false);
        c.endLayer();
    }

    // ---- input --------------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        relayout();
        if (zoom != null) {
            zoom = null;
            return true;
        }
        if (composer.w > 0 && composer.contains(mx, my)) {
            addField.setFocused(false);
            return composer.click(mx, my, button);
        }
        composer.setFocused(false);
        if (addField.w > 0 && addField.contains(mx, my)) return addField.click(mx, my, button);
        addField.setFocused(false);
        if (clickChrome(mx, my, button)) return true;
        if (button != 0) return Vanilla.inside(mx, my, px, py, pw, ph);
        if (tab == FRIENDS && !friends.isEmpty()) {
            if (Vanilla.inside(mx, my, colX, colY, colW, colBottom - colY)) {
                int i = (int) ((my - colY + fscroll) / FRIEND_H);
                if (i >= 0 && i < friends.size() && !Social.same(friends.get(i).uuid(), selected == null ? "" : selected)) {
                    select(friends.get(i).uuid());
                }
                return true;
            }
            Social.Friend f = friend();
            if (f != null && sub.contains(mx, my)) {
                int t = sub.tabAt(mx, my);
                if (t == 0) openView(View.CHAT);
                else if (t == 1) openView(View.PROFILE);
                NavBar.Tool tool = sub.toolAt(mx, my);
                if (tool != null) {
                    switch (tool.id()) {
                        case "shot" -> openView(View.SHOTS);
                        case "invite" -> invite();
                        case "gift" -> giftStore();
                        default -> {}
                    }
                }
                return true;
            }
            if (f != null && f.online() && f.server() != null && Vanilla.inside(mx, my, rx + rw - 6 - 46, headY + 6, 46, BTN_H)) {
                join(f.server());
                return true;
            }
            if (f != null && view == View.CHAT && Vanilla.inside(mx, my, sendX, sendY, sendW, BTN_H)) {
                send();
                return true;
            }
        }
        if (tab == REQUESTS && Vanilla.inside(mx, my, sendX, sendY, sendW, BTN_H)) {
            sendRequest();
            return true;
        }
        if (inList(mx, my)) {
            int y = listY - scroll;
            for (Row r : rows) {
                if (r.click != null && my >= y && my < y + r.h && r.click.click(mx, my, listX, y, listW)) return true;
                y += r.h;
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        if (zoom != null) return true;
        if (tab == FRIENDS && Vanilla.inside(mx, my, colX, colY, colW, colBottom - colY)) {
            fscroll = Math.max(0, Math.min(fscrollMax(), fscroll - (int) Math.signum(amount) * FRIEND_H));
            return true;
        }
        boolean r = super.menuScroll(mx, my, amount);
        if (r) pinBottom = scroll >= maxScroll();
        return r;
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        boolean r = super.menuDrag(mx, my, button);
        if (r) pinBottom = scroll >= maxScroll();
        return r;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (zoom != null && key == InputConstants.KEY_ESCAPE) {
            zoom = null;
            return true;
        }
        TextFieldWidget f = composer.focused() ? composer : addField.focused() ? addField : null;
        if (f != null) {
            boolean mod = (modifiers & Input.MOD_SHORTCUT) != 0;
            if (mod && key == InputConstants.KEY_V) {
                paste(f, f == composer ? Social.MESSAGE_MAX : 16);
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                if (f == composer) send();
                else sendRequest();
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                f.setFocused(false);
                return true;
            }
            return f.keyPressed(key, modifiers);
        }
        if (key == InputConstants.KEY_ESCAPE && tab == FRIENDS && (view == View.SHOTS || view == View.PROFILE)) {
            openView(View.CHAT);
            return true;
        }
        return false;
    }

    @Override
    protected boolean menuChar(char ch) {
        if (composer.focused()) return composer.charTyped(ch);
        if (addField.focused()) return addField.charTyped(ch);
        // start typing anywhere in a conversation to write in it
        if (ch > ' ' && zoom == null && tab == FRIENDS && view == View.CHAT && composer.w > 0) {
            composer.setFocused(true);
            return composer.charTyped(ch);
        }
        return false;
    }

    private void paste(TextFieldWidget f, int max) {
        String clip = mc.keyboardHandler.getClipboard();
        if (clip == null || clip.isEmpty()) return;
        StringBuilder clean = new StringBuilder();
        for (char ch : clip.replaceAll("[\\r\\n\\t]+", " ").toCharArray()) if (ch >= ' ') clean.append(ch);
        String cur = f.text();
        int room = Math.max(0, max - cur.length());
        f.setText(cur + clean.substring(0, Math.min(room, clean.length())));
    }

    @Override
    public void removed() {
        super.removed();
        heads.releaseAll();
        pics.releaseAll();
        thumbs.releaseAll();
        io.shutdownNow();
        worker.shutdown();
    }

    // ---- text -------------------------------------------------------------------

    private List<String> wrapF(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            // a single word wider than the line is cut into pieces
            while (this.font.width(word) > max && word.length() > 1) {
                int n = word.length();
                while (n > 1 && this.font.width(word.substring(0, n)) > max) n--;
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(word.substring(0, n));
                word = word.substring(n);
            }
            String next = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(next) <= max || line.isEmpty()) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty() || out.isEmpty()) out.add(line.toString());
        return out;
    }

    /** The launcher's status line. */
    static String status(Social.Friend f) {
        if (f.online() && f.playing() != null) return f.server() != null ? "PLAYING " + f.playing() + " · " + f.server() : "PLAYING " + f.playing();
        if (f.online()) return "ONLINE";
        if (f.lastSeen() <= 0) return "OFFLINE";
        return "LAST SEEN " + ago(f.lastSeen()).toUpperCase(Locale.ROOT);
    }

    static String ago(long unix) {
        long s = Math.max(0, Instant.now().getEpochSecond() - unix);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        return s / 86400 + "d ago";
    }

    private static LocalDateTime local(long unix) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(unix), ZoneId.systemDefault());
    }

    private static LocalDate day(long unix) {
        return local(unix).toLocalDate();
    }

    private static String hm(long unix) {
        return local(unix).format(HM);
    }

    private static String dayLabel(long unix) {
        LocalDate d = day(unix), today = LocalDate.now();
        if (d.equals(today)) return "TODAY";
        if (d.equals(today.minusDays(1))) return "YESTERDAY";
        return local(unix).format(DAY).toUpperCase(Locale.ROOT);
    }
}
