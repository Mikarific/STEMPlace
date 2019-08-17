package space.pxls.server;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.typesafe.config.Config;
import io.undertow.websockets.core.WebSocketChannel;
import space.pxls.App;
import space.pxls.data.DBChatMessage;
import space.pxls.data.DBPixelPlacement;
import space.pxls.user.Role;
import space.pxls.user.User;
import space.pxls.util.ChatFilter;
import space.pxls.util.PxlsTimer;
import space.pxls.util.RateLimitFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang3.StringEscapeUtils.escapeHtml4;

public class PacketHandler {
    private UndertowServer server;
    private PxlsTimer userData = new PxlsTimer(5);
    private int numAllCons = 0;
    private Config config = App.getConfig();

    public int getCooldown() {
        // TODO: make these parameters somehow configurable

        if (config.getBoolean("useStaticCooldown")) {
            return (int)config.getDuration("cooldown", TimeUnit.SECONDS);
        }
        // exponential function of form a*exp(-b*(x - d)) + c
        double a = -8.04044740e+01;
        double b = 2.73880499e-03;
        double c = 9.63956320e+01;
        double d = -2.14065886e+00;
        double multiplier = config.getDouble("activityCooldown.multiplier");
        double x = 1;
        if (config.getBoolean("activityCooldown.enabled")) {
            x = (double)server.getAuthedUsers().size();
        }
        return (int)(Math.ceil(a*Math.exp(-b*(x - d)) + c) * multiplier);
    }

    public PacketHandler(UndertowServer server) {
        this.server = server;
    }

    public void userdata(WebSocketChannel channel, User user) {
        if (user != null) {
            server.send(channel, new ServerUserInfo(
                    user.getName(),
                    user.getRole().name().equals("SHADOWBANNED") ? "USER" : user.getRole().name(),
                    user.isBanned(),
                    user.isBanned() ? user.getBanExpiryTime() : 0,
                    user.isBanned() ? user.getBanReason() : "",
                    user.getLogin().split(":")[0],
                    user.isOverridingCooldown(),
                    user.isChatbanned(),
                    App.getDatabase().getChatbanReasonForUser(user.getId()),
                    user.isPermaChatbanned(),
                    user.getChatbanExpiryTime(),
                    user.isRenameRequested(true)
            ));
            sendAvailablePixels(channel, user, "auth");
        }
    }

    public void connect(WebSocketChannel channel, User user) {
        if (user != null) {
            userdata(channel, user);
            sendCooldownData(channel, user);
            user.flagForCaptcha();
            server.addAuthedUser(user);

            user.setInitialAuthTime(System.currentTimeMillis());
            user.tickStack(false); // pop the whole pixel stack
            sendAvailablePixels(channel, user, "connect");
        }
        numAllCons++;

        updateUserData();
    }

    public void disconnect(WebSocketChannel channel, User user) {
        if (user != null && user.getConnections().size() == 0) {
            server.removeAuthedUser(user);
        }
        numAllCons--;

        updateUserData();
    }

    public void accept(WebSocketChannel channel, User user, Object obj, String ip) {
        if (user != null) {
            if (obj instanceof ClientPlace) {
                handlePlace(channel, user, ((ClientPlace) obj), ip);
            }
            if (obj instanceof ClientUndo) handleUndo(channel, user, ((ClientUndo) obj), ip);
            if (obj instanceof ClientCaptcha) handleCaptcha(channel, user, ((ClientCaptcha) obj));
            if (obj instanceof ClientShadowBanMe) handleShadowBanMe(channel, user, ((ClientShadowBanMe) obj));
            if (obj instanceof ClientBanMe) handleBanMe(channel, user, ((ClientBanMe) obj));
            if (obj instanceof ClientChatHistory) handleChatHistory(channel, user, ((ClientChatHistory) obj));
            if (obj instanceof ClientChatbanState) handleChatbanState(channel, user, ((ClientChatbanState) obj));
            if (obj instanceof ClientChatMessage) handleChatMessage(channel, user, ((ClientChatMessage) obj));

            if (user.getRole().greaterEqual(Role.MODERATOR)) {
                if (obj instanceof ClientAdminCooldownOverride)
                    handleCooldownOverride(channel, user, ((ClientAdminCooldownOverride) obj));

                if (obj instanceof ClientAdminMessage)
                    handleAdminMessage(channel, user, ((ClientAdminMessage) obj));
            }
        }
    }

    private void handleAdminMessage(WebSocketChannel channel, User user, ClientAdminMessage obj) {
        User u = App.getUserManager().getByName(obj.getUsername());
        if (u != null) {
            ServerAlert msg = new ServerAlert(escapeHtml4(obj.getMessage()));
            for (WebSocketChannel ch : u.getConnections()) {
                server.send(ch, msg);
            }
        }
    }

    private void handleShadowBanMe(WebSocketChannel channel, User user, ClientShadowBanMe obj) {
        if (user.getRole().greaterEqual(Role.USER)) {
            App.getDatabase().adminLog(String.format("shadowban %s with reason: self-shadowban via script", user.getName()), user.getId());
            user.shadowban("auto-ban via script", 999*24*3600, user);
        }
    }

    private void handleBanMe(WebSocketChannel channel, User user, ClientBanMe obj) {
        String app = obj.getApp();
        App.getDatabase().adminLog(String.format("shadowban %s with reason: auto-ban via script (ap: %s)", user.getName(), app), user.getId());
        user.permaban(String.format("auto-ban via script(ap: %s)", app), 0, user);
    }

    private void handleCooldownOverride(WebSocketChannel channel, User user, ClientAdminCooldownOverride obj) {
        user.setOverrideCooldown(obj.getOverride());
        sendCooldownData(user);
    }

    private void handleUndo(WebSocketChannel channel, User user, ClientUndo cu, String ip){
        boolean _canUndo = user.canUndo(true);
        if (!_canUndo || user.undoWindowPassed()) {
            return;
        }
        if (user.isShadowBanned()) {
            user.setLastUndoTime();
            user.setCooldown(0);
            sendCooldownData(user);
            return;
        }
        DBPixelPlacement thisPixel = App.getDatabase().getUserUndoPixel(user);
        DBPixelPlacement recentPixel = App.getDatabase().getPixelAt(thisPixel.x, thisPixel.y);
        if (thisPixel.id != recentPixel.id) return;

        if (user.lastPlaceWasStack()) {
            user.setStacked(Math.min(user.getStacked() + 1, App.getConfig().getInt("stacking.maxStacked")));
            sendAvailablePixels(user, "undo");
        }
        user.setLastUndoTime();
        user.setCooldown(0);
        DBPixelPlacement lastPixel = App.getDatabase().getPixelByID(thisPixel.secondaryId);
        if (lastPixel != null) {
            App.getDatabase().putUserUndoPixel(lastPixel, user, thisPixel.id);
            App.putPixel(lastPixel.x, lastPixel.y, lastPixel.color, user, false, ip, false, "user undo");
            broadcastPixelUpdate(lastPixel.x, lastPixel.y, lastPixel.color);
            ackUndo(user, lastPixel.x, lastPixel.y);
            sendAvailablePixels(user, "undo");
        } else {
            byte defaultColor = App.getDefaultColor(thisPixel.x, thisPixel.y);
            App.getDatabase().putUserUndoPixel(thisPixel.x, thisPixel.y, defaultColor, user, thisPixel.id);
            App.putPixel(thisPixel.x, thisPixel.y, defaultColor, user, false, ip, false, "user undo");
            broadcastPixelUpdate(thisPixel.x, thisPixel.y, defaultColor);
            ackUndo(user, thisPixel.x, thisPixel.y);
            sendAvailablePixels(user, "undo");
        }
        sendCooldownData(user);
    }

    private void handlePlace(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
        if (!cp.getType().equals("pixel")) {
            handlePlaceMaybe(channel, user, cp, ip);
        }
        if (cp.getX() < 0 || cp.getX() >= App.getWidth() || cp.getY() < 0 || cp.getY() >= App.getHeight()) return;
        if (cp.getColor() < 0 || cp.getColor() >= App.getConfig().getStringList("board.palette").size()) return;
        if (user.isBanned()) return;

        if (user.canPlace() && user.tryGetPlacingLock()) {
            boolean doCaptcha = App.isCaptchaEnabled();
            if (doCaptcha) {
                int pixels = App.getConfig().getInt("captcha.maxPixels");
                if (pixels != 0) {
                    boolean allTime = App.getConfig().getBoolean("captcha.allTime");
                    doCaptcha = (allTime ? user.getPixelsAllTime() : user.getPixels()) < pixels;
                }
            }
            if (user.updateCaptchaFlagPrePlace() && doCaptcha) {
                server.send(channel, new ServerCaptchaRequired());
            } else {
                int c = App.getPixel(cp.getX(), cp.getY());
                boolean canPlace = false;
                if (App.getHavePlacemap()) {
                    int placemapType = App.getPlacemap(cp.getX(), cp.getY());
                    switch (placemapType) {
                        case 0:
                            // Allow normal placement
                            canPlace = c != cp.getColor();
                            break;
                        case 2:
                            // Allow tendril placement
                            int top = App.getPixel(cp.getX(), cp.getY() + 1);
                            int left = App.getPixel(cp.getX() - 1, cp.getY());
                            int right = App.getPixel(cp.getX() + 1, cp.getY());
                            int bottom = App.getPixel(cp.getX(), cp.getY() - 1);

                            int defaultTop = App.getDefaultColor(cp.getX(), cp.getY() + 1);
                            int defaultLeft = App.getDefaultColor(cp.getX() - 1, cp.getY());
                            int defaultRight = App.getDefaultColor(cp.getX() + 1, cp.getY());
                            int defaultBottom = App.getDefaultColor(cp.getX(), cp.getY() - 1);
                            if (top != defaultTop || left != defaultLeft || right != defaultRight || bottom != defaultBottom) {
                                // The pixel has at least one other attached pixel
                                canPlace = c != cp.getColor() && c != 0xFF && c != -1;
                            }
                            break;
                    }
                } else {
                    canPlace = c != cp.getColor() && c != 0xFF && c != -1;
                }
                int c_old = c;
                if (canPlace) {
                    int seconds = getCooldown();
                    if (c_old != 0xFF && c_old != -1 && App.getDatabase().shouldPixelTimeIncrease(cp.getX(), cp.getY(), user.getId()) && App.getConfig().getBoolean("backgroundPixel.enabled")) {
                        seconds = (int)Math.round(seconds * App.getConfig().getDouble("backgroundPixel.multiplier"));
                    }
                    if (user.isShadowBanned()) {
                        // ok let's just pretend to set a pixel...
                        App.logShadowbannedPixel(cp.getX(), cp.getY(), cp.getColor(), user.getName(), ip);
                        ServerPlace msg = new ServerPlace(Collections.singleton(new ServerPlace.Pixel(cp.getX(), cp.getY(), cp.getColor())));
                        for (WebSocketChannel ch : user.getConnections()) {
                            server.send(ch, msg);
                        }
                        if (user.canUndo(false)) {
                            server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                        }
                    } else {
                        boolean mod_action = user.isOverridingCooldown();
                        App.putPixel(cp.getX(), cp.getY(), cp.getColor(), user, mod_action, ip, true, "");
                        App.saveMap();
                        broadcastPixelUpdate(cp.getX(), cp.getY(), cp.getColor());
                        ackPlace(user, cp.getX(), cp.getY());
                    }
                    if (!user.isOverridingCooldown()) {
                        user.setLastPixelTime();
                        if (user.getStacked() > 0) {
                            user.setLastPlaceWasStack(true);
                            user.setStacked(user.getStacked()-1);
                            sendAvailablePixels(user, "consume");
                        } else {
                            user.setLastPlaceWasStack(false);
                            user.setCooldown(seconds);
                            App.getDatabase().updateUserTime(user.getId(), seconds);
                            sendAvailablePixels(user, "consume");
                        }

                        if (user.canUndo(false)) {
                            server.send(channel, new ServerCanUndo(App.getConfig().getDuration("undo.window", TimeUnit.SECONDS)));
                        }
                    }
                }
            }
        }

        user.releasePlacingLock();
        sendCooldownData(user);
    }

    private void handlePlaceMaybe(WebSocketChannel channel, User user, ClientPlace cp, String ip) {
    }

    private void handleCaptcha(WebSocketChannel channel, User user, ClientCaptcha cc) {
        if (!user.isFlaggedForCaptcha()) return;
        if (user.isBanned()) return;

        Unirest
                .post("https://www.google.com/recaptcha/api/siteverify")
                .field("secret", App.getConfig().getString("captcha.secret"))
                .field("response", cc.getToken())
                //.field("remoteip", "null")
                .asJsonAsync(new Callback<JsonNode>() {
                    @Override
                    public void completed(HttpResponse<JsonNode> response) {
                        JsonNode body = response.getBody();

                        String hostname = App.getConfig().getString("host");

                        boolean success = body.getObject().getBoolean("success") && body.getObject().getString("hostname").equals(hostname);
                        if (success) {
                            user.validateCaptcha();
                        }

                        server.send(channel, new ServerCaptchaStatus(success));
                    }

                    @Override
                    public void failed(UnirestException e) {

                    }

                    @Override
                    public void cancelled() {

                    }
                });
    }

    public void handleChatbanState(WebSocketChannel channel, User user, ClientChatbanState clientChatbanState) {
        server.send(channel, new ServerChatbanState(user.isPermaChatbanned(), user.getChatbanExpiryTime()));
    }

    public void handleChatHistory(WebSocketChannel channel, User user, ClientChatHistory clientChatHistory) {
        server.send(channel, new ServerChatHistory(App.getDatabase().getlastXMessagesForSocket(100, false, false)));
    }

    public void handleChatMessage(WebSocketChannel channel, User user, ClientChatMessage clientChatMessage) {
        Long nowMS = System.currentTimeMillis();
        String message = clientChatMessage.getMessage();
        if (message.contains("\r")) message = message.replaceAll("\r", "");
        if (message.endsWith("\n")) message = message.replaceFirst("\n$", "");
        if (message.length() > 2048) message = message.substring(0, 2048);
        if (user == null) { //console
            String nonce = App.getDatabase().insertChatMessage(0, nowMS, message, "");
            server.broadcast(new ServerChatMessage(new ChatMessage(nonce, "CONSOLE", nowMS / 1000L, message, null)));
        } else {
            if (user.isChatbanned()) return;
            if (message.trim().length() == 0) return;
            if (user.isRenameRequested(false)) return;
            int remaining = RateLimitFactory.getTimeRemaining(DBChatMessage.class, String.valueOf(user.getId()));
            if (remaining > 0) {
                server.send(user, new ServerChatCooldown(remaining, message));
            } else {
                try {
                    if (App.getConfig().getBoolean("chat.trimInput"))
                        message = message.trim();
                    if (App.getConfig().getBoolean("chat.filter.enabled")) {
                        ChatFilter.FilterResult result = ChatFilter.getInstance().filter(message);
                        String nonce = App.getDatabase().insertChatMessage(user.getId(), nowMS, message, result.filterHit ? result.filtered : "");
                        server.broadcast(new ServerChatMessage(new ChatMessage(nonce, user.getName(), nowMS / 1000L, result.filterHit ? result.filtered : result.original, user.getChatBadges())));
                    } else {
                        String nonce = App.getDatabase().insertChatMessage(user.getId(), nowMS, message, "");
                        server.broadcast(new ServerChatMessage(new ChatMessage(nonce, user.getName(), nowMS / 1000L, message, user.getChatBadges())));
                    }
                } catch (org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException utese) {
                    //ignore for now. caused by emojis TODO add emoji support
                }
            }
        }
    }

    public void sendChatban(User user, ServerChatBan chatban) {
        server.send(user, chatban);
    }

    public void sendChatPurge(User target, User initiator, int amount, String reason) {
        server.broadcast(new ServerChatPurge(target.getName(), initiator == null ? "CONSOLE" : initiator.getName(), amount, reason));
    }

    public void sendSpecificPurge(User target, User initiator, String nonce, String reason) {
        sendSpecificPurge(target, initiator, Collections.singletonList(nonce), reason);
    }

    public void sendSpecificPurge(User target, User initiator, List<String> nonces, String reason) {
        server.broadcast(new ServerChatSpecificPurge(target.getName(), initiator == null ? "CONSOLE" : initiator.getName(), nonces, reason));
    }

    private void updateUserData() {
        userData.run(() -> {
            server.broadcast(new ServerUsers(server.getAuthedUsers().size()));
        });
    }

    private void sendCooldownData(WebSocketChannel channel, User user) {
        server.send(channel, new ServerCooldown(user.getRemainingCooldown()));
    }

    private void sendCooldownData(User user) {
        for (WebSocketChannel ch: user.getConnections()) {
            sendCooldownData(ch, user);
        }
    }

    private void broadcastPixelUpdate(int x, int y, int color) {
        server.broadcast(new ServerPlace(Collections.singleton(new ServerPlace.Pixel(x, y, color))));
    }

    public void sendAvailablePixels(WebSocketChannel ch, User user, String cause) {
        server.send(ch, new ServerPixels(user.getAvailablePixels(), cause));
    }
    public void sendAvailablePixels(User user, String cause) {
        for (WebSocketChannel ch : user.getConnections()) {
            sendAvailablePixels(ch, user, cause);
        }
    }

    private void ackUndo(User user, int x, int y) {
        ack(user, "UNDO", x, y);
    }

    private void ackPlace(User user, int x, int y) {
        ack(user, "PLACE", x, y);
    }

    private void ack(User user, String _for, int x, int y) {
        for (WebSocketChannel ch : user.getConnections()) {
            server.send(ch, new ServerACK(_for, x, y));
        }
    }

    public int getNumAllCons() {
        return numAllCons;
    }
}
