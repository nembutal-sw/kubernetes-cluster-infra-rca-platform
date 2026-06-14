<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="referrer" content="no-referrer">
    <title>Cluster Infra RCA Console</title>
    <link rel="stylesheet" href="/webjars/bootstrap/5.3.3/css/bootstrap.min.css">
    <link rel="stylesheet" href="/webjars/bootstrap-icons/1.11.3/font/bootstrap-icons.css">
    <link rel="stylesheet" href="/assets/console.css">
  </head>
  <body>
    <noscript>
      <main class="container py-5">
        <h1>Cluster Infra RCA Console</h1>
        <p>JavaScript is required to operate this console.</p>
      </main>
    </noscript>
    <div
      id="rca-console-root"
      data-api-base="${apiBasePath}"
      data-public-api-base="${publicApiBaseUrl}">
    </div>
    <script src="/webjars/bootstrap/5.3.3/js/bootstrap.bundle.min.js"></script>
    <script src="/webjars/react/18.2.0/umd/react.production.min.js"></script>
    <script src="/webjars/react-dom/18.2.0/umd/react-dom.production.min.js"></script>
    <script src="/assets/console-app.js"></script>
  </body>
</html>
