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

    const response = await fetch(
      `https://yts.mx/api/v2/list_movies.json?query_term=${encodeURIComponent(q)}`
    );

    const data = await response.json();

    res.json(data);

  } catch (err) {
    console.error(err);

    res.status(500).json({
      error: err.message
    });
  }
});

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
  console.log('Server started on port', PORT);
});
