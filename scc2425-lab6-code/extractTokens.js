const fs = require('fs');
const path = require('path');

// Define the input and output files
const inputFile = 'output.json';
const csvFile = path.join(__dirname, 'shorts_for_blobs.csv');
const csvStream = fs.createWriteStream(csvFile, { flags: 'w' });

// Write the CSV header
csvStream.write('shortId,token\n');

// Read and parse each line of the input file
const lines = fs.readFileSync(inputFile, 'utf8').split('\n');
lines.forEach((line) => {
  // Match the shortId and token in each line
  const match = line.match(/^([^,]+),[^?]+\?token=(.+)$/);
  if (match) {
    const shortId = match[1];
    const token = match[2]; // Extract the token from the blobUrl
    csvStream.write(`${shortId},${token}\n`);
  }
});

csvStream.end(() => {
  console.log('CSV file created successfully at', csvFile);
});
