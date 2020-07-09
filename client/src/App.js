import React from "react";
import "./App.css";

function App() {
  return (
    <div className="App">
      <h1>journaltogether</h1>
      <h3>Keep track of your days and connect with your friends</h3>
      <ol>
        <li>Choose a few friends</li>
        <li>
          Every evening, each of you will receive an email, asking about your
          day
        </li>
        <li>Each of you write a reflection</li>
        <li>
          The next morning, you'll all receive an email of all reflections
        </li>
      </ol>
      <h4>
        Interested?{" "}
        <a href="mailto:stepan.p@gmail.com" rel="noopener noreferrer" target="_blank">
          send a ping : )
        </a>
      </h4>
    </div>
  );
}

export default App;
