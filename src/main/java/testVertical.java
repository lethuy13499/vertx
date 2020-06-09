import com.github.rjeschke.txtmark.Processor;
import freemarker.core.TemplateMarkupOutputModel;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.freemarker.FreeMarkerTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class testVertical extends AbstractVerticle {
  private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
  private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
  private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
  private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
  private static final String SQL_ALL_PAGES = "select Name from Pages";
  private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";
  private JDBCClient client;
  private static final Logger LOGGER = LoggerFactory.getLogger(testVertical.class);

  private Future<Void> prepareDatabase() {
    Promise<Void> promise = Promise.promise();// promise định nghĩa cho non-blocking operation(thực hiện ko tuần tự)
    client = JDBCClient.createShared(vertx, new JsonObject() // createShared tạo ra 1 kết nối được share giữa các vertical
      .put("url", "jdbc:hsqldb:file:db/wiki")
      .put("driver_class", "org.hsqldb.jdbcDriver")
      .put("max_pool_size", 30));//là số lượng kết nối đồng thời => có thể để giá trị khác
    client.getConnection(ar -> {  //get connection một hoạt động không đồng bộ cung cấp cho chúng tôi AsyncResult <SQLConnection>.
                                  // Sau đó, nó phải được kiểm tra để xem liệu kết nối có thể được thiết lập hay không
      if (ar.failed()) {
        LOGGER.error("not connect", ar.cause());
        promise.fail(ar.cause());//nếu ko connect dc thì phương thức future ko thành công vs ngoại lệ AsyncResult cung cấp thông qua phương thức cause()

      } else {
        SQLConnection connection = ar.result();//SQLConnection là kết quả của AsyncResult thành công.có thể sử dụng nó để thực hiện một truy vấn SQL
        connection.execute(SQL_CREATE_PAGES_TABLE, create -> {
          connection.close();//đóng kết nối

          if (create.failed()) {
            LOGGER.error("Database preparation ", create.cause());//nếu ko thực  hiện dc câu query ->logg error
            promise.fail(create.cause());
          } else {
            promise.complete();//hoàn thành thành công future
          }
        });
      }
    });
    return promise.future();// future() là phương thức trả về Future để thông báo về việc hoàn thành promise và trả về giá trị của nó
  }

  private FreeMarkerTemplateEngine templateEngine;

  private Future<Void> startHttpServer() {
    Promise<Void> promise = Promise.promise();
    HttpServer server = vertx.createHttpServer();//cung cấp phương thức để tạo máy chủ HTTP clients, TCP/UDP servers and clients
    Router router = Router.router(vertx); //khởi tạo 1 router từ vertx-web: io.vertx.ext.web.Router
    router.get("/").handler(this::indexHandler);
    router.get("/wiki/:page").handler(this::pageRenderingHandler);
    router.post().handler(BodyHandler.create());//tất cả các yêu cầu post http đi qua 1 trình xử lý đầu tiên là io.vertx.ext.web.handler.BodyHandlr
    // Trình xử lý này tự động giải mã phần thân từ các yêu cầu HTTP , sau đó có thể được thao tác dưới dạng Vert.x buffer objects.
    router.post("/save").handler(this::pageUpdateHandler);
    router.post("/create").handler(this::pageCreateHandler);
    router.post("/delete").handler(this::pageDeletionHandler);
    templateEngine = FreeMarkerTemplateEngine.create(vertx);
    server
      .requestHandler(router)  //router dc sử dụng như 1 trình xử lí máy chủ HTTP
      .listen(8080, ar -> {//khởi động http server là 1 hoạt động ko đồng bộ do đó AsyncResult<HttpServer> cần dc check thành công.8080 dc chỉ đinh là cổng TCP
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port 8080");
          promise.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          promise.fail(ar.cause());
        }
      });
    return promise.future();
  }

  private static final String EMPTY_PAGE_MARKDOWN =
    "# A new page\n" +
      "\n" +
      "Feel-free to write in Markdown!\n";

  private void pageRenderingHandler(RoutingContext context) {
    String page = context.request().getParam("page");
    client.getConnection(car -> {
      if (car.succeeded()) {
        SQLConnection connection = car.result();
        connection.queryWithParams(SQL_GET_PAGE, new JsonArray().add(page), fetch -> {
          connection.close();
          if (fetch.succeeded()) {
            JsonArray row = fetch.result().getResults().stream().findFirst().orElseGet(() -> new JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN));
            Integer id = row.getInteger(0);
            String rawContent = row.getString(1);
            context.put("title", page);
            context.put("id", id);
            context.put("newPage", fetch.result().getResults().size() == 0 ? "yes" : "no");
            context.put("rawContent", rawContent);
            context.put("content", Processor.process(rawContent));
            context.put("timestamp", new Date().toString());
            templateEngine.render(context.data(), "templates/page.ftl", ar -> {
              if (ar.succeeded()) {
                context.response().putHeader("Content-Type", "text/html");
                context.response().end(ar.result());
              } else {
                context.fail(ar.cause());
              }
            });
          } else {
            context.fail(fetch.cause());
          }
        });

      } else {
        context.fail(car.cause());
      }
    });
  }

  private void pageUpdateHandler(RoutingContext context) {
    String id = context.request().getParam("id");
    String title = context.request().getParam("title");
    String markdown = context.request().getParam("markdown");
    boolean newPage = "yes".equals(context.request().getParam("newPage"));

    client.getConnection(car -> {
      if (car.succeeded()) {
        SQLConnection connection = car.result();
        String sql = newPage ? SQL_CREATE_PAGE : SQL_SAVE_PAGE;
        JsonArray params = new JsonArray();
        if (newPage) {
          params.add(title).add(markdown);
        } else {
          params.add(markdown).add(id);
        }
        connection.updateWithParams(sql, params, res -> {
          connection.close();
          if (res.succeeded()) {
            context.response().setStatusCode(303);
            context.response().putHeader("Location", "/wiki/" + title);
            context.response().end();
          } else {
            context.fail(res.cause());
          }
        });
      } else {
        context.fail(car.cause());
      }
    });
  }

  private void pageCreateHandler(RoutingContext context) {
    String pageName = context.request().getParam("name");
    String location = "/wiki/" + pageName;
    if (pageName == null || pageName.isEmpty()) {
      location = "/";
    }
    context.response().setStatusCode(303);
    context.response().putHeader("Location", location);
    context.response().end();
  }


  private void pageDeletionHandler(RoutingContext context) {
    String id = context.request().getParam("id");
    client.getConnection(car -> {
      if (car.succeeded()) {
        SQLConnection connection = car.result();
        connection.updateWithParams(SQL_DELETE_PAGE, new JsonArray().add(id), res -> {
          connection.close();
          if (res.succeeded()) {
            context.response().setStatusCode(303);
            context.response().putHeader("Location", "/");
            context.response().end();
          } else {
            context.fail(res.cause());
          }
        });
      } else {
        context.fail(car.cause());
      }
    });
  }

  private void indexHandler(RoutingContext context) {
    client.getConnection(car -> {
      if (car.succeeded()) {
        SQLConnection connection = car.result();
        connection.query(SQL_ALL_PAGES, res -> {
          connection.close();
          if (res.succeeded()) {
            List<String> pages = res.result()
              .getResults()
              .stream()
              .map(json -> json.getString(0))
              .sorted()
              .collect(Collectors.toList());

            context.put("title", "Wiki home");
            context.put("pages", pages);
            templateEngine.render(context.data(), "templates/index.ftl", ar -> {
              if (ar.succeeded()) {
                context.response().putHeader("Content-Type", "text/html");
                context.response().end(ar.result());
              } else {
                context.fail(ar.cause());
              }
            });

          } else {
            context.fail(res.cause());
          }
        });
      } else {
        context.fail(car.cause());
      }
    });
  }
  public void start(Promise<Void> promise) throws Exception {
    Future<Void> steps = this.prepareDatabase().compose((v) -> {
      return this.startHttpServer();
    });
    steps.setHandler(promise);
  }

  public void anotherStart(Promise<Void> promise) throws Exception {
    Future<Void> steps = this.prepareDatabase().compose((v) -> {
      return this.startHttpServer();
    });
    steps.setHandler((ar) -> {
      if (ar.succeeded()) {
        promise.complete();
      } else {
        promise.fail(ar.cause());
      }

    });
  }
}


