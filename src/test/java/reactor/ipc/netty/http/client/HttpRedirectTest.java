/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.client;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.resources.PoolResources;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpRedirectTest {

	private final int numberOfTests = 1000;

	@Test
	public void deadlockWhenRedirectsToSameUrl(){
		redirectTests("/login");
	}

	@Test
	public void okWhenRedirectsToOther(){
		redirectTests("/other");
	}

	private void redirectTests(String url) {
		AtomicInteger counter = new AtomicInteger(1);
		NettyContext server =
				HttpServer.create(9999)
				          .newHandler((req, res) -> {
				              if (req.uri().contains("/login") &&
				                      req.method().equals(HttpMethod.POST) &&
				                      counter.getAndDecrement() > 0) {
				                  return res.sendRedirect("http://localhost:9999" + url);
				              }
				              else {
				                  return res.status(200)
				                            .send();
				              }
				          })
				          .block(Duration.ofSeconds(30));

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient client =
				HttpClient.create(ops -> ops.connectAddress(() -> server.address())
				                            .poolResources(pool));

		try {
			Flux.range(0, this.numberOfTests)
			    .concatMap(i -> client.post("/login", r -> r.followRedirect())
			                          .flatMap(r -> r.receive()
			                                         .then()))
			    .blockLast(Duration.ofSeconds(30));
		}
		finally {
			server.dispose();
		}

	}

	@Test
	public void testIssue253() {
		NettyContext server =
				HttpServer.create(9991)
				          .newRouter(r -> r.get("/1",
				                                   (req, res) -> res.sendRedirect("http://localhost:9991/3"))
				                           .get("/2",
				                                   (req, res) -> res.status(301)
				                                                    .header(HttpHeaderNames.LOCATION, "http://localhost:9991/3")
				                                                    .send())
				                           .get("/3",
				                                   (req, res) -> res.status(200)
				                                                    .sendString(Mono.just("OK"))))
				          .block(Duration.ofSeconds(30));

		HttpClient client =
				HttpClient.create(ops -> ops.connectAddress(() -> server.address()));

		String value =
				client.get("/1", req -> req.followRedirect().send())
				      .flatMap(res -> res.receive().aggregate().asString())
				      .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isEqualTo("OK");

		value = client.get("/1")
		              .flatMap(res -> res.receive().aggregate().asString())
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isNull();

		value = client.get("/2", req -> req.followRedirect().send())
		              .flatMap(res -> res.receive().aggregate().asString())
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isEqualTo("OK");

		value = client.get("/2")
		              .flatMap(res -> res.receive().aggregate().asString())
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isNull();

		server.dispose();
	}
}
