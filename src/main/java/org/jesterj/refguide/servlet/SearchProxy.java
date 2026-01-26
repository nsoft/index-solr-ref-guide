package org.jesterj.refguide.servlet;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;

public class SearchProxy extends HttpServlet {

  private HttpClient jettyClient;
  private InetSocketAddress solr;

  @Override
  public void init() throws ServletException {
    jettyClient = new HttpClient();
    try {
      jettyClient.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    solr = new InetSocketAddress("localhost", 8981);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

    // make sure that the user can't utilize any undesirable request parameters.
    // such as collection= to search other collections.
    req = new ParameterMungingWrapper(req);

    try {
      // whatever they send us it's going to go to select on localhost
      // and our wrapper will delete annoying parameters and peg defType to edismax.
      ContentResponse http = jettyClient.GET(new URI("http", null, solr.getHostName(), solr.getPort(), "/solr/ref_guide/select", req.getQueryString(), null));
      HttpFields headers = http.getHeaders();
      headers.stream().forEach(h -> {
        if (h.getHeader() != null) {
          resp.setHeader(h.getHeader().toString(), h.getValue());
        }
      });
      resp.getOutputStream().write(http.getContent());
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      throw new RuntimeException(e);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
  }

  /**
   * Http request wrapper that hides a defined set of request parameters from downstream operations.
   */
  private static class ParameterMungingWrapper extends HttpServletRequestWrapper {

    // This may not be exhaustive, but good enough for now.
    static String[] DANGER = new String[]{
        "collection",
        "debug",
        "explainOther",
        "_query_",
        "defType",
        "uf" // IIUC this is sufficient to ensure no localparams pop us out of edismax, see (SOLR-11501)
    };

    // since these are gnarly regexes we don't want to recompile frequently.
    private static final Map<String, Pattern> BAD_PARAMS;

    static {
      Map<String, Pattern> tmp = new HashMap<>();
      for (String disallowedParam : DANGER) {
        disallowedParam = encodingSafeRegex(disallowedParam);
        // abusing regex here to make this logic fully reusable in all cases even though capitalization is often
        // significant, and some methods we are checking may have already decoded the URL. This strategy
        // works in all places at the cost of performance. For best performance, treat each individually
        // after careful assessment of which cases are exposed to encoded strings. (which I didn't bother to do).
        tmp.put(disallowedParam, Pattern.compile(disallowedParam + "=[^&]*&|&" + disallowedParam + "=[^&]*$"));
      }
      BAD_PARAMS = Collections.unmodifiableMap(tmp);
    }

    public ParameterMungingWrapper(HttpServletRequest req) {
      super(req);
    }

    @Override
    public String getQueryString() {
      // this method is super annoying because we can't access the underlying map
      // thus we have to process the string representation with regex.
      String result = super.getQueryString();
      if (result == null) {
        return null;
      }
      String tmp;
      do {
        tmp = result;
        for (String disallowedParam : BAD_PARAMS.keySet()) {
          result = deleteParameter(disallowedParam, tmp);
        }
        // parameters could be in query string multiple times, need to loop
        // until deleteParameter has no effect.
      } while (tmp.length() != result.length());
      result += "&defType=edismax";
      return result;
    }

    private String deleteParameter(String param, String queryString) {

      if (queryString == null) {
        return "";
      }
      Pattern pattern = BAD_PARAMS.get(param);
      if (pattern == null) {
        return queryString;
      }
      return pattern.matcher(queryString).replaceAll("");
    }

    public static String encodingSafeRegex(String simple) {
      StringBuffer result = new StringBuffer(simple.length() * 25);
      simple.chars().forEach(c -> {
        result.append("(?:[");
        Character ch = (char) c;
        // character might be either capitalization - possibly we can skip this
        // I think we treat parameters as case-sensitive, but not 100% sure about that.
        // overly conservative for now...
        String lowerCase = ch.toString().toLowerCase();
        result.append(lowerCase);
        String upperCase = ch.toString().toUpperCase();
        result.append(upperCase);
        String hexUpper = Integer.toHexString(upperCase.charAt(0));
        String hexLower = Integer.toHexString(lowerCase.charAt(0));
        if (hexUpper.length() > 2 || hexLower.length() > 2) {
          // fail requests with this sort of hackery, can only be malicious/broken
          throw new RuntimeException("Improperly encoded URL contained a character greater than %FF");
        }
        // character could be (wrongly) url encoded
        result.append("]|%").append(hexUpper);
        result.append("|%").append(hexLower);

        // wrong url coding may be using upper case for one or both alpha-numeric components
        String huC1 = hexUpper.substring(0, 1);
        String huC2 = hexUpper.substring(1);
        if (StringUtils.isAlpha(huC1)) {
          result.append("|%").append(huC1.toUpperCase()).append(huC2);
        }
        if (StringUtils.isAlpha(huC2)) {
          result.append("|%").append(huC1).append(huC2.toUpperCase());
        }
        if (StringUtils.isAlpha(huC1) && StringUtils.isAlpha(huC2)) {
          result.append("|%").append(huC1.toUpperCase()).append(huC2.toUpperCase());
        }
        result.append(")");
      });
      return result.toString();
    }

    @Override
    public String getParameter(String name) {
      if (BAD_PARAMS.values().stream().anyMatch(pat -> pat.matcher(name).matches())) {
        if (BAD_PARAMS.get("defType").matcher(name).matches()) {
          return "edismax";
        }
        return null;
      }
      return super.getParameter(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      Map<String, String[]> parameterMap = new HashMap<>(super.getParameterMap());

      return Collections.unmodifiableMap(
          parameterMap.entrySet().stream()
              .filter(e ->
                  BAD_PARAMS.values()
                      .stream()
                      .noneMatch(pat -> pat.matcher(e.getKey()).matches())
              )
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return Collections.enumeration(
          Collections.list(
                  super.getParameterNames())
              .stream()
              .filter(p -> BAD_PARAMS.values()
                  .stream()
                  .noneMatch(pat -> pat.matcher(p).matches()))
              .collect(Collectors.toList()));
    }

    @Override
    public String[] getParameterValues(String name) {
      if (BAD_PARAMS.values().stream().anyMatch(pat -> pat.matcher(name).matches())) {
        return null;
      }
      return super.getParameterValues(name);
    }
  }
}
