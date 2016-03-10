package com.pubnub.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;

abstract class PubnubCoreAsync extends PubnubCore {

    public PubnubCoreAsync() {
        
    }
    
    private volatile boolean resumeOnReconnect;

    public static boolean daemonThreads = false;

    private Subscriptions channelSubscriptions;
    private Subscriptions channelGroupSubscriptions;

    protected TimedTaskManager timedTaskManager;
    private volatile String _timetoken = "0";
    private volatile String _saved_timetoken = "0";

    protected static String PRESENCE_SUFFIX = "-pnpres";
    protected static String WILDCARD_SUFFIX = "*";
    protected static String WILDCARD_PRESENCE_SUFFIX = WILDCARD_SUFFIX + PRESENCE_SUFFIX;

    private static Logger log = new Logger(PubnubCore.class);

    private int PRESENCE_HEARTBEAT_TASK = 0;
    private int HEARTBEAT = 320;
    private volatile int PRESENCE_HB_INTERVAL = 0;
    
    private SubscribeState SUBSCRIBE_STATE = SubscribeState.INIT;

    private PubnubCoreAsync pn = this;

    public void shutdown() {
        nonSubscribeManager.stop();
        subscribeManager.stop();
        timedTaskManager.stop();
    }

    public boolean isResumeOnReconnect() {
        return resumeOnReconnect;
    }

    void setRetryInterval(int retryInterval) {
        subscribeManager.setRetryInterval(retryInterval);
    }

    void setWindowInterval(int windowInterval) {
        subscribeManager.setWindowInterval(windowInterval);
    }

    public int getRetryInterval() {
        return subscribeManager.retryInterval;
    }

    public int getWindowInterval() {
        return subscribeManager.windowInterval;
    }

    String[] getPresenceHeartbeatUrl() {
        String channelString = channelSubscriptions.getItemStringNoPresence();
        Result result = new Result();
        if (channelString.length() <= 0) {
            return null;
        }
        return new String[] { getPubnubUrl(result), "v2", "presence", "sub-key", this.SUBSCRIBE_KEY, "channel",
                PubnubUtil.urlEncode(channelString), "heartbeat" };
    }

    private String getState() {
        return (channelSubscriptions.state.length() > 0) ? channelSubscriptions.state.toString() : null;
    }

    class PresenceHeartbeatTask extends TimedTask {
        private Callback callback;

        PresenceHeartbeatTask(int interval, Callback callback) {
            super(interval);
            this.callback = callback;
        }

        public void run() {

            Result result = new Result();
            String[] urlComponents = getPresenceHeartbeatUrl();
            if (urlComponents == null)
                return;
            // String[] urlComponents = { getPubnubUrl(), "time", "0"};

            Hashtable parameters = PubnubUtil.hashtableClone(params);
            if (parameters.get("uuid") == null)
                parameters.put("uuid", UUID);

            String st = getState();
            if (st != null)
                parameters.put("state", st);

            if (HEARTBEAT > 0 && HEARTBEAT < 320)
                parameters.put("heartbeat", String.valueOf(HEARTBEAT));

            HttpRequest hreq = new HttpRequest(urlComponents, parameters, new ResponseHandler() {
                public void handleResponse(HttpRequest hreq, String response, Result result) {
                    JSONObject jso;
                    try {
                        jso = new JSONObject(response);
                        response = jso.getString("message");
                    } catch (JSONException e) {
                        handleError(hreq, 
                                PubnubError.getErrorObject(PubnubError.PNERROBJ_INVALID_JSON, 1, response)
                                , result);
                        return;
                    }
                    callback.successCallback(channelSubscriptions.getItemStringNoPresence(), response, result);
                }

                public void handleError(HttpRequest hreq, PubnubError error, Result result) {
                    callback.errorCallback(channelSubscriptions.getItemStringNoPresence(), error, result);
                }
            }, result);

            _request(hreq, nonSubscribeManager);

        }

    }


    public void setHeartbeat(int heartbeat, Callback callback) {
        Callback cb = getWrappedCallback(callback);

        HEARTBEAT = (heartbeat > 0 && heartbeat < 5) ? 5 : heartbeat;
        if (PRESENCE_HB_INTERVAL == 0) {
            PRESENCE_HB_INTERVAL = (HEARTBEAT - 3 >= 1) ? HEARTBEAT - 3 : 1;
        }
        if (PRESENCE_HEARTBEAT_TASK == 0) {
            PRESENCE_HEARTBEAT_TASK = timedTaskManager.addTask("Presence-Heartbeat", new PresenceHeartbeatTask(
                    PRESENCE_HB_INTERVAL, cb));
        } else if (PRESENCE_HB_INTERVAL == 0 || PRESENCE_HB_INTERVAL > 320) {
            timedTaskManager.removeTask(PRESENCE_HEARTBEAT_TASK);
        } else {
            timedTaskManager.updateTask(PRESENCE_HEARTBEAT_TASK, PRESENCE_HB_INTERVAL);
        }
        disconnectAndResubscribe();
    }

    public void setHeartbeat(int heartbeat) {
        setHeartbeat(heartbeat, null);
    }

    public void setHeartbeatInterval(int heartbeatInterval) {
        setHeartbeatInterval(heartbeatInterval, null);
    }

    public void setHeartbeatInterval(int heartbeatInterval, Callback callback) {

        Callback cb = getWrappedCallback(callback);
        PRESENCE_HB_INTERVAL = heartbeatInterval;
        if (PRESENCE_HEARTBEAT_TASK == 0) {
            PRESENCE_HEARTBEAT_TASK = timedTaskManager.addTask("Presence-Heartbeat", new PresenceHeartbeatTask(
                    PRESENCE_HB_INTERVAL, cb));
        } else if (PRESENCE_HB_INTERVAL == 0 || PRESENCE_HB_INTERVAL > 320) {
            timedTaskManager.removeTask(PRESENCE_HEARTBEAT_TASK);
        } else {
            timedTaskManager.updateTask(PRESENCE_HEARTBEAT_TASK, PRESENCE_HB_INTERVAL);
        }

    }

    public int getHeartbeatInterval() {
        return PRESENCE_HB_INTERVAL;
    }

    public int getPnExpires() {
        return getHeartbeat();
    }

    public int getHeartbeat() {
        return HEARTBEAT;
    }

    public int getMaxRetries() {
        return subscribeManager.maxRetries;
    }


    public boolean getCacheBusting() {
        return this.CACHE_BUSTING;
    }

    public String getCurrentlySubscribedChannelNames() {
        String currentChannels = channelSubscriptions.getItemString();
        return currentChannels.equals("") ? "no channels." : currentChannels;
    }
    
    public boolean getResumeOnReconnect() {
        return this.resumeOnReconnect;
    }


    Random random = new Random();

    void initAsync() {

        if (channelSubscriptions == null)
            channelSubscriptions = new Subscriptions();

        if (channelGroupSubscriptions == null)
            channelGroupSubscriptions = new Subscriptions();

        if (subscribeManager == null)
            subscribeManager = new SubscribeManager("Subscribe-Manager-" + System.identityHashCode(this), 10000,
                    310000, daemonThreads);

        if (nonSubscribeManager == null)
            nonSubscribeManager = new NonSubscribeManager("Non-Subscribe-Manager-" + System.identityHashCode(this),
                    10000, 15000, daemonThreads);

        if (timedTaskManager == null)
            timedTaskManager = new TimedTaskManager("TimedTaskManager");

        subscribeManager.setHeader("V", VERSION);
        subscribeManager.setHeader("Accept-Encoding", "gzip");
        subscribeManager.setHeader("User-Agent", getUserAgent());

        nonSubscribeManager.setHeader("V", VERSION);
        nonSubscribeManager.setHeader("Accept-Encoding", "gzip");
        nonSubscribeManager.setHeader("User-Agent", getUserAgent());

    }

    protected int getSubscribeTimeout() {
        return subscribeManager.requestTimeout;
    }

    protected int getNonSubscribeTimeout() {
        return nonSubscribeManager.requestTimeout;
    }


    void presence(String channel, Callback callback) throws PubnubException {
        Hashtable args = new Hashtable(2);

        args.put("channels", new String[] { channel + PRESENCE_SUFFIX });
        args.put("callback", callback);

        subscribe(args);
    }

    void channelGroupPresence(String group, Callback callback) throws PubnubException {
        Hashtable args = new Hashtable(2);

        args.put("groups", new String[] { group + PRESENCE_SUFFIX });
        args.put("callback", callback);

        subscribe(args);
    }

    void whereNow(final String uuid, Callback callback) {
        _whereNow(uuid, callback, false);
    }

    void whereNow(Callback callback) {
        whereNow(this.UUID, callback);
    }

    void setState(String channel, String uuid, JSONObject state, Callback callback) {
        _setState(channelSubscriptions, PubnubUtil.urlEncode(channel), null, uuid, state, callback, false);
    }

    void channelGroupSetState(String group, String uuid, JSONObject state, Callback callback) {
        _setState(channelSubscriptions, ".", group, uuid, state, callback, false);
    }

    protected void setState(Subscriptions sub, String channel, String group, String uuid, JSONObject state,
            Callback callback) {
        _setState(sub, channel, group, uuid, state, callback, true);
    }

    void getState(String channel, String uuid, Callback callback) {
        _getState(channel, uuid, callback, false);
    }

    void channelGroupListGroups(String namespace, Callback callback) {
        _channelGroupListGroups(null, callback, false);
    }

    void channelGroupListGroups(Callback callback) {
        channelGroupListGroups(null, callback);
    }

    void channelGroupListChannels(String group, Callback callback) {
        _channelGroupListChannels(group, callback, false);
    }

    void channelGroupAddChannel(String group, String channel, Callback callback) {
        channelGroupUpdate("add", group, new String[] { channel }, callback);
    }

    void channelGroupAddChannel(String group, String[] channels, Callback callback) {
        channelGroupUpdate("add", group, channels, callback);
    }

    void channelGroupRemoveChannel(String group, String channel, Callback callback) {
        channelGroupUpdate("remove", group, new String[] { channel }, callback);
    }

    void channelGroupRemoveChannel(String group, String[] channels, Callback callback) {
        channelGroupUpdate("remove", group, channels, callback);
    }

    private void channelGroupUpdate(String action, String group, String[] channels, final Callback callback) {
        _channelGroupUpdate(action, group, channels, callback, false);
    }

    void channelGroupRemoveGroup(String group, Callback callback) {
        _channelGroupRemoveGroup(group, callback, false);
    }

    void hereNow(final String channel, Callback callback) {
        hereNow(new String[] { channel }, null, false, true, callback);
    }

    public void hereNow(boolean state, boolean uuids, Callback callback) {
        hereNow(null, null, state, uuids, callback);
    }

    void hereNow(final String channel, boolean state, boolean uuids, Callback callback) {
        hereNow(new String[] { channel }, null, state, uuids, callback);
    }

    void channelGroupHereNow(String group, Callback callback) {
        channelGroupHereNow(group, false, true, callback);
    }

    void channelGroupHereNow(String group, boolean state, boolean uuids, Callback callback) {
        channelGroupHereNow(new String[] { group }, state, uuids, callback);
    }

    void channelGroupHereNow(String[] groups, boolean state, boolean uuids, Callback callback) {
        hereNow(null, groups, state, uuids, callback);
    }

    void hereNow(String[] channels, String[] channelGroups, boolean state, boolean uuids, Callback callback) {
        _hereNow(channels, channelGroups, state, uuids, callback, false);
    }

    void history(final String channel, long start, long end, int count, boolean reverse, Callback callback) {
        history(channel, start, end, count, reverse, false, callback);
    }

    void history(final String channel, long start, long end, int count, boolean reverse,
            boolean includeTimetoken, Callback callback) {
        _history(channel, start, end, count, reverse, includeTimetoken, callback, false);
    }

    void history(String channel, long start, long end, boolean reverse, Callback callback) {
        history(channel, start, end, -1, reverse, callback);
    }

    void history(String channel, int count, Callback callback) {
        history(channel, -1, -1, count, false, callback);
    }

    void history(String channel, boolean includeTimetoken, int count, Callback callback) {
        history(channel, -1, -1, count, false, includeTimetoken, callback);
    }

    void history(String channel, long start, boolean reverse, Callback callback) {
        history(channel, start, -1, -1, reverse, callback);
    }

    void history(String channel, long start, long end, Callback callback) {
        history(channel, start, end, -1, false, callback);
    }

    void history(String channel, long start, long end, int count, Callback callback) {
        history(channel, start, end, count, false, callback);
    }

    void history(String channel, long start, int count, boolean reverse, Callback callback) {
        history(channel, start, -1, count, reverse, callback);
    }

    void history(String channel, long start, int count, Callback callback) {
        history(channel, start, -1, count, false, callback);
    }

    void history(String channel, int count, boolean reverse, Callback callback) {
        history(channel, -1, -1, count, reverse, callback);
    }

    void history(String channel, boolean reverse, Callback callback) {
        history(channel, -1, -1, -1, reverse, callback);
    }

    public void time(Callback callback) {
        _time(callback, false);
    }

    private void _leave(String channel, Callback callback) {
        _leave(channel, null, PubnubUtil.hashtableClone(this.params), callback);
    }

    private void _leave(String channel) {
        _leave(channel, null);
    }

    private void channelGroupLeave(String group) {
        channelGroupLeave(group, null);
    }

    private void channelGroupLeave(String group, Callback callback) {
        _leave(null, group, PubnubUtil.hashtableClone(this.params), callback);
    }

    private void _leave(String[] channels, String[] channelGroups, Hashtable params) {
        _leave(channels, channelGroups, params, null);
    }

    private void _leave(String[] channels, String[] channelGroups, Hashtable params, Callback callback) {
        _leave(PubnubUtil.joinString(channels, ","), PubnubUtil.joinString(channelGroups, ","), params, callback);
    }

    private void _leave(String[] channels, String[] channelGroups) {
        _leave(channels, channelGroups, PubnubUtil.hashtableClone(this.params), null);
    }

    private void _leave(String[] channels, String[] channelGroups, Callback callback) {
        _leave(PubnubUtil.joinString(channels, ","), PubnubUtil.joinString(channelGroups, ","),
                PubnubUtil.hashtableClone(this.params), callback);
    }

    private void _leave(String channel, String channelGroup, Callback callback) {
        _leave(channel, channelGroup, PubnubUtil.hashtableClone(this.params), callback);
    }

    private void _leave(String channel, String channelGroup, Hashtable params, Callback callback) {

        final Callback cb = getWrappedCallback(callback);
        
        Result result = new Result();

        if (PubnubUtil.isEmptyString(channel) && PubnubUtil.isEmptyString(channelGroup))
            return;

        if (PubnubUtil.isEmptyString(channel))
            channel = ",";

        String[] urlArgs = { getPubnubUrl(result), "v2/presence/sub_key", this.SUBSCRIBE_KEY, "channel",
                PubnubUtil.urlEncode(channel), "leave" };

        params.put("uuid", UUID);

        if (!PubnubUtil.isEmptyString(channelGroup))
            params.put("channel-group", channelGroup);

        HttpRequest hreq = new HttpRequest(urlArgs, params, new ResponseHandler() {
            public void handleResponse(HttpRequest hreq, String response, Result result) {
                cb.successCallback(null, response, result);
            }

            public void handleError(HttpRequest hreq, PubnubError error, Result result) {
                cb.errorCallback(null, error, result);
            }
        }, result);

        _request(hreq, nonSubscribeManager);
    }

    void unsubscribe(String[] channels, Callback callback) {
        for (int i = 0; i < channels.length; i++) {
            String channel = channels[i];
            channelSubscriptions.removeItem(channel);
            channelSubscriptions.state.remove(channel);
        }
        _leave(channels, null, callback);
        resubscribe();
    }

    /**
     * Unsubscribe from multiple channel groups
     *
     * @param groups
     *            to unsubscribe
     * @param callback
     *            Callback
     */
    public void channelGroupUnsubscribe(String[] groups, Callback callback) {
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            channelGroupSubscriptions.removeItem(group);
        }
        _leave(null, groups, callback);
        resubscribe();
    }


    /**
     * Unsubscribe from presence channel.
     *
     * @param channel
     *            channel name as String.
     * @param callback
     *            Callback
     */
    public void unsubscribePresence(String channel, Callback callback) {
        unsubscribe(new String[] { channel + PRESENCE_SUFFIX }, callback);
    }

    /**
     * Unsubscribe from all channels and channel groups.
     *
     * @param callback
     */
    public void unsubscribeAll(Callback callback) {
        String[] channels = channelSubscriptions.getItemNames();
        String[] groups = channelGroupSubscriptions.getItemNames();

        for (int i = 0; i < channels.length; i++) {
            String channel = channels[i];
            channelSubscriptions.removeItem(channel);
        }

        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            channelGroupSubscriptions.removeItem(group);
        }
        _leave(channels, groups, callback);
        disconnectAndResubscribe();
    }

    public void unsubscribeAll() {
        unsubscribeAll(null);
    }

    void unsubscribeAllChannels() {
        unsubscribeAllChannels(null);
    }

    void unsubscribeAllChannels(Callback callback) {
        String[] channels = channelSubscriptions.getItemNames();

        for (int i = 0; i < channels.length; i++) {
            String channel = channels[i];
            channelSubscriptions.removeItem(channel);
        }
        _leave(channels, null, callback);

        disconnectAndResubscribe();
    }


    void channelGroupUnsubscribeAllGroups(Callback callback) {
        String[] groups = channelGroupSubscriptions.getItemNames();

        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            channelGroupSubscriptions.removeItem(group);
        }
        _leave(null, groups, callback);

        disconnectAndResubscribe();
    }

    protected void subscribe(Hashtable args, Callback callback) throws PubnubException {
        args.put("callback", callback);

        subscribe(args);
    }

    protected void subscribe(Hashtable args) throws PubnubException {

        keepOnlyPluralSubscriptionItems(args);

        if (!inputsValid(args)) {
            return;
        }

        _subscribe(args);
    }

    protected void subscribe(String[] channels, Callback callback) throws PubnubException {
        subscribe(channels, callback, "0");
    }

    protected void subscribe(String[] channels, Callback callback, String timetoken) throws PubnubException {

        Hashtable args = new Hashtable();

        if (callback == null) callback = this.globalCallback;

        args.put("channels", channels);
        args.put("callback", callback);
        args.put("timetoken", timetoken);

        subscribe(args);
    }


    protected void subscribe(String[] channels, String[] groups, Callback callback, String timetoken)
            throws PubnubException {
        Hashtable args = new Hashtable();

        if (callback == null) callback = this.globalCallback;

        args.put("channels", channels);
        args.put("groups", groups);
        args.put("callback", callback);
        args.put("timetoken", timetoken);

        subscribe(args);
    }

    protected void channelGroupSubscribe(String[] groups, Callback callback, String timetoken) throws PubnubException {

        Hashtable args = new Hashtable();

        args.put("groups", groups);
        args.put("callback", callback);
        args.put("timetoken", timetoken);

        subscribe(args);
    }

    protected void callErrorCallbacks(String[] channelList, PubnubError error, Result result) {
        for (int i = 0; i < channelList.length; i++) {
            String channel = channelList[i];
            Callback cb = channelSubscriptions.getItem(channel).callback;
            cb.errorCallback(channel, error, result);
        }
    }

    private void _subscribe(Hashtable args) {

        String[] channelList = (String[]) args.get("channels");
        String[] groupList = (String[]) args.get("groups");


        if (channelList == null) {
            channelList = new String[0];
        }

        if (groupList == null) {
            groupList = new String[0];
        }

        Callback callback = (Callback) args.get("callback");
        if (callback == null) callback = this.globalCallback;
        String timetoken = (String) args.get("timetoken");

        if (!_timetoken.equals("0"))
            _saved_timetoken = _timetoken;
        _timetoken = (timetoken == null) ? "0" : timetoken;

        /*
         * Scan through the channels array. If a channel does not exist in
         * hashtable create a new item with default values. If already exists
         * and connected, then return
         */

        for (int i = 0; i < channelList.length; i++) {
            String channel = channelList[i];

            if (channel.endsWith(WILDCARD_SUFFIX + PRESENCE_SUFFIX)) {
                String messagesChannel = channel.substring(0, channel.indexOf(PRESENCE_SUFFIX));

                SubscriptionItem wildcardMessagesObj = (SubscriptionItem) channelSubscriptions.getItem(messagesChannel);
                SubscriptionItem wildcardPresenceObj = (SubscriptionItem) channelSubscriptions.getItem(channel);

                if (wildcardMessagesObj == null) {
                    SubscriptionItem ch = new SubscriptionItem(messagesChannel, callback);

                    channelSubscriptions.addItem(ch);
                }

                if (wildcardPresenceObj == null) {
                    SubscriptionItem pr = new SubscriptionItem(channel, callback);

                    channelSubscriptions.addItem(pr);
                }
            } else {
                SubscriptionItem channelObj = (SubscriptionItem) channelSubscriptions.getItem(channel);

                if (channelObj == null) {
                    SubscriptionItem ch = new SubscriptionItem(channel, callback);

                    channelSubscriptions.addItem(ch);
                }
            }
        }

        for (int i = 0; i < groupList.length; i++) {
            String group = groupList[i];
            SubscriptionItem channelGroupObj = (SubscriptionItem) channelGroupSubscriptions.getItem(group);

            if (channelGroupObj == null) {
                SubscriptionItem chg = new SubscriptionItem(group, callback);

                channelGroupSubscriptions.addItem(chg);
            }
        }

        _subscribe_base(true);
    }

    private void _subscribe_base(boolean fresh) {
        _subscribe_base(fresh, false, null);
    }

    private void _subscribe_base(boolean fresh, boolean dar) {
        _subscribe_base(fresh, dar, null);
    }

    private void _subscribe_base(Worker worker) {
        _subscribe_base(false, false, worker);
    }

    private void _subscribe_base(boolean fresh, Worker worker) {
        _subscribe_base(fresh, false, worker);
    }

    private boolean isWorkerDead(HttpRequest hreq) {
        return (hreq == null || hreq.getWorker() == null) ? false : hreq.getWorker()._die;
    }

    private void _subscribe_base(boolean fresh, boolean dar, Worker worker) {
        String channelString = channelSubscriptions.getItemString(WILDCARD_PRESENCE_SUFFIX);
        String groupString = channelGroupSubscriptions.getItemString();
        String[] channelsArray = channelSubscriptions.getItemNames(WILDCARD_PRESENCE_SUFFIX);
        String[] groupsArray = channelGroupSubscriptions.getItemNames();

        final SubscribeResult result = new SubscribeResult();

        if (channelsArray.length <= 0 && groupsArray.length <= 0) {
            subscribeManager.resetHttpManager();
            return;
        }

        if (channelString == null) {
            callErrorCallbacks(channelsArray, PubnubError.PNERROBJ_PARSING_ERROR, result);
            return;
        }

        if (channelString.equals("")) {
            channelString = ",";
        } else {
            channelString = PubnubUtil.urlEncode(channelString);
        }

        String[] urlComponents = { getPubnubUrl(result), "subscribe", this.SUBSCRIBE_KEY,
                channelString, "0" + "/" + _timetoken};

        Hashtable params = PubnubUtil.hashtableClone(this.params);
        params.put("uuid", UUID);

        if (groupsArray.length > 0) {
            params.put("channel-group", groupString);
        }

        String st = getState();
        if (st != null)
            params.put("state", st);

        if (HEARTBEAT > 5 && HEARTBEAT < 320)
            params.put("heartbeat", String.valueOf(HEARTBEAT));
        log.verbose("Subscribing with timetoken : " + _timetoken);


        HttpRequest hreq = new HttpRequest(urlComponents, params, new ResponseHandler() {

            void v1Handler(JSONArray jsa, HttpRequest hreq, SubscribeResult result) throws JSONException {
                System.out.println("V1 handler");
                JSONArray messages = new JSONArray(jsa.get(0).toString());

                if (jsa.length() == 4) {
                    /*
                     * Response has multiple channels or/and groups
                     */
                    String[] _groups = PubnubUtil.splitString(jsa.getString(2), ",");
                    String[] _channels = PubnubUtil.splitString(jsa.getString(3), ",");

                    for (int i = 0; i < _channels.length; i++) {
                        handleFourElementsSubscribeResponse(_groups[i],
                                _channels[i], messages.get(i), _timetoken, hreq, result);
                    }
                } else if (jsa.length() == 3) {
                    /*
                     * Response has multiple channels
                     */

                    String[] _channels = PubnubUtil.splitString(jsa.getString(2), ",");

                    for (int i = 0; i < _channels.length; i++) {
                        SubscriptionItem _channel = channelSubscriptions.getItem(_channels[i]);
                        Object message = messages.get(i);

                        if (_channel != null) {
                            invokeSubscribeCallback(_channel.name,
                                    _channel.callback, message, _timetoken, hreq, result);
                        }
                    }
                } else if (jsa.length() < 3) {
                    /*
                     * Response for single channel Callback on single channel
                     */
                    SubscriptionItem _channel = channelSubscriptions.getFirstItem();

                    if (_channel != null) {
                        for (int i = 0; i < messages.length(); i++) {
                            Object message = messages.get(i);
                            invokeSubscribeCallback(_channel.name,
                                    _channel.callback, message, _timetoken, hreq, result);
                        }
                    }

                }

            }
            @Override
            public void handleResponse(HttpRequest hreq, String response, Result result1) {


                SubscribeResult result = (SubscribeResult)result1;

                JSONArray jsa = null;

                JSONObject jso = null;

                String _in_response_timetoken = "";

                try {
                    jsa = new JSONArray(response);
                    _in_response_timetoken = jsa.get(1).toString();

                } catch (JSONException e) {

                    if (hreq.isSubzero()) {
                        log.verbose("Response of subscribe 0 request. Need to do dAr process again");
                        _subscribe_base(false, hreq.isDar(), hreq.getWorker());
                    } else
                        _subscribe_base(false);
                    return;

                }

                /*
                 * Check if response has channel names. A JSON response with
                 * more than 2 items means the response contains the channel
                 * names as well. The channel names are in a comma delimted
                 * string. Call success callback on all he channels passing the
                 * corresponding response message.
                 */

                _timetoken = (!_saved_timetoken.equals("0") && isResumeOnReconnect()) ? _saved_timetoken
                        : _in_response_timetoken;
                log.verbose("Resume On Reconnect is " + isResumeOnReconnect());
                log.verbose("Saved Timetoken : " + _saved_timetoken);
                log.verbose("In Response Timetoken : " + _in_response_timetoken);
                log.verbose("Timetoken value set to " + _timetoken);
                _saved_timetoken = "0";
                log.verbose("Saved Timetoken reset to 0");

                //StreamStatus status = new StreamStatus(new StreamResult(result));

                if (!hreq.isDar()) {
                    channelSubscriptions.invokeConnectCallbackOnItems(_timetoken, result);
                    channelGroupSubscriptions.invokeConnectCallbackOnItems(_timetoken, result);


                    //status.status.category = StatusCategory.CONNECT;

                } else {
                    channelSubscriptions.invokeReconnectCallbackOnItems(_timetoken, result);
                    channelGroupSubscriptions.invokeReconnectCallbackOnItems(_timetoken, result);

                   // status.status.category = StatusCategory.RECONNECT;

                }

                if (pn.globalCallback != null) {
                    if (SUBSCRIBE_STATE != SubscribeState.CONNECTED) {
                        //pn.globalCallback.subscribeCallback(status);
                        SUBSCRIBE_STATE = SubscribeState.CONNECTED;
                    }
                }

                try {

                    v1Handler(jsa, hreq, result);

                } catch (JSONException e) {

                }
                if (hreq.isSubzero()) {
                    log.verbose("Response of subscribe 0 request. Need to do dAr process again");
                    _subscribe_base(false, hreq.isDar(), hreq.getWorker());
                } else
                    _subscribe_base(false);
            }

            public void handleBackFromDar(HttpRequest hreq) {
                _subscribe_base(false, hreq.getWorker());
            }
            @Override
            public void handleError(HttpRequest hreq, PubnubError error, Result result) {
                disconnectAndResubscribe(error);
            }

            public void handleTimeout(HttpRequest hreq) {
                log.verbose("Timeout Occurred, Calling disconnect callbacks on the channels");
                String timeoutTimetoken = (isResumeOnReconnect()) ? (_timetoken.equals("0")) ? _saved_timetoken
                        : _timetoken : "0";
                log.verbose("Timeout Timetoken : " + timeoutTimetoken);
                
                channelSubscriptions.invokeDisconnectCallbackOnItems(timeoutTimetoken, result);
                channelGroupSubscriptions.invokeDisconnectCallbackOnItems(timeoutTimetoken, result);
                channelSubscriptions.invokeErrorCallbackOnItems(PubnubError.getErrorObject(
                        PubnubError.PNERROBJ_TIMEOUT, 1), result);
                channelGroupSubscriptions.invokeErrorCallbackOnItems(PubnubError.getErrorObject(
                        PubnubError.PNERROBJ_TIMEOUT, 1), result);
                // disconnectAndResubscribe();

                // channelSubscriptions.removeAllItems();
            }

            public String getTimetoken() {
                return _timetoken;
            }
        }, result);
        if (_timetoken.equals("0")) {
            hreq.setSubzero(true);
            log.verbose("This is a subscribe 0 request");
        }
        hreq.setDar(dar);
        if (worker != null && worker instanceof Worker)
            hreq.setWorker(worker);

        setResultData(result, OperationType.SUBSCRIBE, hreq);
        _request(hreq, subscribeManager, fresh);
    }

    private void handleFourElementsSubscribeResponse(String thirdString, String fourthString, Object message,
            String timetoken, HttpRequest hreq, SubscribeResult result) throws JSONException {

        SubscriptionItem thirdChannelGroup = channelGroupSubscriptions.getItem(thirdString);
        SubscriptionItem thirdChannel = channelSubscriptions.getItem(thirdString);
        SubscriptionItem fourthChannel = channelSubscriptions.getItem(fourthString);

        if (isWorkerDead(hreq))
            return;

        if (thirdString.equals(fourthString) && fourthChannel != null) {
            invokeSubscribeCallback(fourthString, fourthChannel.callback, message, timetoken, hreq, result);
        } else if (thirdString.endsWith("*")) {
            if (fourthChannel != null && fourthString.endsWith(PRESENCE_SUFFIX)) {
                invokeSubscribeCallback(fourthString, fourthChannel.callback, message, timetoken, hreq, result);
            } else if (thirdChannelGroup != null && !fourthString.endsWith(PRESENCE_SUFFIX)) {
                invokeSubscribeCallback(fourthString, thirdChannelGroup.callback, message, timetoken, hreq, result);
            } else if (thirdChannel != null && thirdString.endsWith(WILDCARD_SUFFIX)
                    && !fourthString.endsWith(PRESENCE_SUFFIX) /*
                                                                * !!! get
                                                                * reviewed by
                                                                * Alex
                                                                */) {
                invokeSubscribeCallback(fourthString, thirdChannel.callback, message, timetoken, hreq, result);
            } else {
                // !!! This should be handled by error Callback. Or use logging
                // mechanism
                // System.out.println("ERROR: Unable to handle wildcard response: "
                // + message);
            }
        } else if (!thirdString.equals(fourthString) && thirdChannelGroup != null) {
            invokeSubscribeCallback(fourthString, thirdChannelGroup.callback, message, timetoken, hreq, result);
        } else {
            // !!!! This should be handled in error callback. Or use logging
            // mechanism.
            // System.out.println("ERROR: Unable to handle response: " +
            // message);
        }
    }


    HashMap<String,StreamListener> listeners = new HashMap<String, StreamListener>();

    Callback globalCallback = new Callback(){

        @Override
        void successCallback(String channel, Object response, Result result) {
            StreamResult res = new StreamResult((SubscribeResult)result);
            res.data.channel = channel;
            for (StreamListener listener : listeners.values()) {
                listener.streamResult(res);
            }
        }

        @Override
        void errorCallback(String channel, PubnubError error, Result result) {

        }

        @Override
        void connectCallback(String channel, Object response, SubscribeResult result) {
            StreamStatus status = new StreamStatus(new StreamResult(result));
            status.isError = false;
            status.category = StatusCategory.CONNECT;
            status.data.channel = channel;
            statusOnAll(status);
        }

        @Override
        void reconnectCallback(String channel, Object response, SubscribeResult result) {

        }

        @Override
        void disconnectCallback(String channel, Object response, SubscribeResult result) {

        }

        public synchronized  void statusOnAll(StreamStatus status) {
            for (StreamListener listener : listeners.values()) {
                listener.streamStatus(status);
            }
        }
        
        public synchronized  void resultOnAll(StreamResult result) {
            for (StreamListener listener : listeners.values()) {
                listener.streamResult(result);
            }
        }

        public synchronized  void subscribeCallback(SubscribeResult result) {
            System.out.println(result);
            StreamResult res = new StreamResult(result);
            for (StreamListener listener : listeners.values()) {
                listener.streamResult(res);
            }
        }
        public void subscribeCallback(StreamStatus status) {
            status.isError = false;
            status.type = ResultType.STATUS;
            status.wasAutoRetried = true;
            statusOnAll(status);
        }

    };

    public synchronized void addStreamListener(String id, StreamListener listener) {
        listeners.put(id, listener);
    }

    public synchronized  void removeAllListeners() {
        listeners.clear();
    }

    public synchronized void removeListener(String id) {
        listeners.remove(id);
    }


    private void invokeSubscribeCallback(String channel, Callback callback, Object message, String timetoken,
            HttpRequest hreq, SubscribeResult result) throws JSONException {
        System.out.println("INVOKE SUBSCRIBE CALLBACK");
        if (callback == null) {
            callback = this.globalCallback;
        }

        if (CIPHER_KEY.length() > 0 && !channel.endsWith(PRESENCE_SUFFIX)) {
            PubnubCrypto pc = new PubnubCrypto(CIPHER_KEY, IV);
            try {
                message = pc.decrypt(message.toString());
                if (!isWorkerDead(hreq))
                    callback.successWrapperCallback(channel,
                            PubnubUtil.parseJSON(PubnubUtil.stringToJSON(message.toString())), timetoken, result);
            } catch (IllegalStateException e) {
                if (!isWorkerDead(hreq))
                    callback.errorCallback(channel,
                            PubnubError.getErrorObject(PubnubError.PNERROBJ_DECRYPTION_ERROR, 12,
                                    message.toString()), result);
            } catch (PubnubException e) {
                if (!isWorkerDead(hreq))
                    callback.errorCallback(
                            channel,
                            getPubnubError(e, PubnubError.PNERROBJ_DECRYPTION_ERROR, 16,
                                    message.toString() + " : " + e.toString()), result);
            } catch (Exception e) {
                if (!isWorkerDead(hreq))
                    callback.errorCallback(
                            channel,
                            PubnubError.getErrorObject(PubnubError.PNERROBJ_DECRYPTION_ERROR, 15, message.toString()
                                    + " : " + e.toString()), result);
            }
        } else {
            if (!isWorkerDead(hreq))
                callback.successWrapperCallback(channel, PubnubUtil.parseJSON(message), timetoken, result);
        }
    }

    private void changeOrigin() {
        this.ORIGIN_STR = null;
        this.HOSTNAME_SUFFIX = getRandom();
    }

    private void resubscribe() {
        changeOrigin();
        if (!_timetoken.equals("0"))
            _saved_timetoken = _timetoken;
        _timetoken = "0";
        log.verbose("Before Resubscribe Timetoken : " + _timetoken);
        log.verbose("Before Resubscribe Saved Timetoken : " + _saved_timetoken);
        _subscribe_base(true, true);
    }

    private void resubscribe(String timetoken) {
        changeOrigin();
        if (!timetoken.equals("0"))
            _saved_timetoken = timetoken;
        _timetoken = "0";
        log.verbose("Before Resubscribe Timetoken : " + _timetoken);
        log.verbose("Before Resubscribe Saved Timetoken : " + _saved_timetoken);
        _subscribe_base(true, true);
    }

    public void disconnectAndResubscribeWithTimetoken(String timetoken) {
        disconnectAndResubscribeWithTimetoken(timetoken, PubnubError.PNERROBJ_DISCONN_AND_RESUB);
    }

    public void disconnectAndResubscribeWithTimetoken(String timetoken, PubnubError error) {
        log.verbose("Received disconnectAndResubscribeWithTimetoken");
        channelSubscriptions.invokeErrorCallbackOnItems(error, null);
        channelGroupSubscriptions.invokeErrorCallbackOnItems(error, null);
        resubscribe(timetoken);
    }

    public void disconnectAndResubscribe() {
        disconnectAndResubscribe(PubnubError.PNERROBJ_DISCONNECT);
    }

    public void disconnectAndResubscribe(PubnubError error) {
        log.verbose("Received disconnectAndResubscribe");
        channelSubscriptions.invokeErrorCallbackOnItems(error, null);
        channelGroupSubscriptions.invokeErrorCallbackOnItems(error, null);
        resubscribe();
    }

    public String[] getSubscribedChannelsArray() {
        return channelSubscriptions.getItemNames();
    }

    public void setAuthKey(String authKey) {
        super.setAuthKey(authKey);
        resubscribe();
    }

    public void unsetAuthKey() {
        super.unsetAuthKey();
        resubscribe();
    }
    
    
    
    public PubnubSubscribe pubnubSubscribe = new PubnubSubscribe((Pubnub)this);

    public PubnubSubscribe subscribe() {
        return pubnubSubscribe;
    }

    
    PubnubPublishAsync pubnubPublishAsync = new PubnubPublishAsync((Pubnub)this);
    
    PubnubHistoryAsync pubnubHistoryAsync = new PubnubHistoryAsync((Pubnub)this);
    
    public PubnubPublishAsync publish() {
        return pubnubPublishAsync;
    }
    
    public PubnubHistoryAsync history() {
        return pubnubHistoryAsync;
    }
    
    PubnubPamAsync pubnubPamAsync = new PubnubPamAsync((Pubnub)this);
    
    public PubnubPamAsync pam() {
        return pubnubPamAsync;
    }
    
    PubnubCGAsync pubnubCGAsync = new PubnubCGAsync((Pubnub)this);
    
    public PubnubCGAsync channelGroup() {
        return pubnubCGAsync;
    }
    
    PubnubWhereNowAsync pubnubWhereNowAsync = new PubnubWhereNowAsync((Pubnub)this);
    
    
    public PubnubWhereNowAsync whereNow() {
        return pubnubWhereNowAsync;
    }
    
    PubnubHereNowAsync pubnubHereNowAsync = new PubnubHereNowAsync((Pubnub)this);
    
    
    public PubnubHereNowAsync hereNow() {
        return pubnubHereNowAsync;
    }
    
    PubnubAsyncUnsubscribe pubnubUnsubscribeAsync = new PubnubAsyncUnsubscribe((Pubnub)this);
    
    public PubnubAsyncUnsubscribe unsubscribe() {
        return pubnubUnsubscribeAsync;
    }
    
    PubnubAsyncState pubnubAsyncState = new PubnubAsyncState((Pubnub)this);
    
    public PubnubAsyncState state() {
        return pubnubAsyncState;
    }
    
}
