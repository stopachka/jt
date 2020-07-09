import React from "react";
import "./App.css";
import { withRouter } from "react-router"
import { BrowserRouter as Router, Switch, Route } from "react-router-dom";
import * as firebase from 'firebase/app';
import qs from 'qs';

// Set up Firebase
import 'firebase/auth';
import 'firebase/database';
firebase.initializeApp({
  apiKey: "AIzaSyCBl-YAARDZuf0KcTIOTcmUZjynaKC7puc",
  authDomain: "journaltogether.firebaseapp.com",
  databaseURL: "https://journaltogether.firebaseio.com",
  projectId: "journaltogether",
  storageBucket: "journaltogether.appspot.com",
  messagingSenderId: "625725315087",
  appId: "1:625725315087:web:a946ce7cf0725381df2b7a",
  measurementId: "G-GYBL76DZQW"
});

function serverPath(path) { 
  const root = process.env.NODE_ENV === 'development'
    ? 'http://localhost:8080'
    : ''
  return `${root}/${path}`
}

class AuthComp extends React.Component {
  constructor(props) {
    super(props);
  }

  componentDidMount() {
    const code = qs.parse(this.props.location.search, { ignoreQueryPrefix: true })['mc'];
    fetch(
      serverPath('api/auth'),
      {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'magic-code': code}),
      }
    ).then(x => x.status === 200 ? x.json() : Promise.reject("uh oh"))
    .then(({token}) => {
      firebase.auth().signInWithCustomToken(token);
    })
    .catch(() => {
      console.log('todo');
    });
    // Ask server to auth user
    // get token and validate firebase
    // listen to changes for firebase
  }

  render() {
    return <h1>Welcome to the account page!</h1>
  }
}

const Auth = withRouter(AuthComp);

function Home() {
  return (
    <div className="Home">
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
        <a
          href="mailto:stepan.p@gmail.com"
          rel="noopener noreferrer"
          target="_blank"
        >
          send a ping : )
        </a>
      </h4>
    </div>
  );
}

class App extends React.Component {
  render() {
    return (
      <div className="App">
        <Router>
          <Switch>
            <Route path="/mc-auth">
              <Auth />
            </Route>
            <Route path="/">
              <Home />
            </Route>
          </Switch>
        </Router>
      </div>
    );
  }
}

export default App;
