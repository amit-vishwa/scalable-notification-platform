package io.github.amitvishwa.notification;

import io.github.amitvishwa.notification.operator.replay.OperatorReplayArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

@SpringBootApplication
public class ScalableNotificationPlatformApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(
				ScalableNotificationPlatformApplication.class,
				args
		);

		if (context.getEnvironment().acceptsProfiles(
				Profiles.of(OperatorReplayArguments.PROFILE)
		)) {
			context.close();
		}
	}
}