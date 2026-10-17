package io.github.amitvishwa.notification.operator.replay;

import io.github.amitvishwa.notification.application.replay.ManualReplayResult;
import io.github.amitvishwa.notification.application.replay.NotificationReplayService;
import io.github.amitvishwa.notification.domain.model.ManualReplayRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile(OperatorReplayArguments.PROFILE)
public class OperatorReplayRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(OperatorReplayRunner.class);

    private final NotificationReplayService replayService;
    private final ManualReplayRequest request;

    public OperatorReplayRunner(
            NotificationReplayService replayService,
            ManualReplayRequest request
    ) {
        this.replayService = replayService;
        this.request = request;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        ManualReplayResult result = replayService.replay(request);

        log.info(
                "Manual replay recorded: commandId={}, "
                        + "notificationId={}, requeuedVersion={}",
                result.commandId(),
                result.notificationId(),
                result.requeuedVersion()
        );
    }
}