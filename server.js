const express = require('express');
const cors = require('cors');

const app = express();

app.use(cors());

app.get('/', (req, res) => {
  res.send('YTS proxy running');
});

app.get('/search', async (req, res) => {
  try {
    const q = req.query.q || '';

    console.log('Searching:', q);

    const url =
      `https://yts.mx/api/v2/list_movies.json?query_term=${encodeURIComponent(q)}`;

    console.log('URL:', url);

    const response = await fetch(url);

    console.log('Status:', response.status);
    console.log('Status Text:', response.statusText);

    const text = await response.text();

    console.log('Response Body:');
    console.log(text);

    res.status(response.status).send(text);

  } catch (err) {
    console.error('FULL ERROR:', err);

    res.status(500).json({
      error: err.message,
      cause: err.cause?.message || null
    });
  }
});

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
  console.log('Server started on port', PORT);
});
