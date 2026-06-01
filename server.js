const express = require('express');
const cors = require('cors');

const app = express();

app.use(cors());

app.get('/search', async (req, res) => {
  try {
    const q = req.query.q || '';

    const response = await fetch(
      `https://apibay.org/q.php?q=${encodeURIComponent(q)}`
    );

    const data = await response.json();

    res.json(data);
  } catch (err) {
    res.status(500).json({
      error: err.message
    });
  }
});

app.get('/', (req, res) => {
  res.send('ApiBay proxy running');
});

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
  console.log('Server started on port', PORT);
});
