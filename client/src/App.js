import React from "react";
import "./App.css";
import { withRouter } from "react-router";
import { BrowserRouter as Router, Switch, Route } from "react-router-dom";
import * as firebase from "firebase/app";
import qs from "qs";

// Set up Firebase
import "firebase/auth";
import "firebase/database";
firebase.initializeApp({
  apiKey: "AIzaSyCBl-YAARDZuf0KcTIOTcmUZjynaKC7puc",
  authDomain: "journaltogether.firebaseapp.com",
  databaseURL: "https://journaltogether.firebaseio.com",
  projectId: "journaltogether",
  storageBucket: "journaltogether.appspot.com",
  messagingSenderId: "625725315087",
  appId: "1:625725315087:web:a946ce7cf0725381df2b7a",
  measurementId: "G-GYBL76DZQW",
});

function serverPath(path) {
  const root =
    process.env.NODE_ENV === "development" ? "http://localhost:8080" : "";
  return `${root}/${path}`;
}

class MeComp extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      isAuthenticating: true,
      isLoading: true,
      isLoggedIn: null,
      errorMessage: null,
    };
  }

  componentDidMount() {
    window.signOut = () => firebase.auth().signOut();
    this.handleMagicCode();
    this.listenToAuth();
  }

  handleMagicCode() {
    const code = qs.parse(this.props.location.search, {
      ignoreQueryPrefix: true,
    })["mc"];
    if (!code) {
      this.setState({ isAuthenticating: false });
      return;
    }
    fetch(serverPath("api/auth"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ "magic-code": code }),
    })
      .then((x) => (x.status === 200 ? x.json() : Promise.reject("uh oh")))
      .then(({ token }) => firebase.auth().signInWithCustomToken(token))
      .then(
        () => {
          this.setState({ isAuthenticating: false });
        },
        () => {
          this.setState({
            isAuthenticating: false,
            errorMessage: "Uh oh. failed to log in please ping Stopa",
          });
        }
      );
  }
  listenToAuth() {
    firebase.auth().onAuthStateChanged((user) => {
      this.setState({
        isLoading: false,
        isLoggedIn: !!user,
      });
    });
  }
  render() {
    const {
      isLoading,
      isAuthenticating,
      isLoggedIn,
      errorMessage,
    } = this.state;
    if (errorMessage) {
      return <div>Uh oh we got an error</div>;
    }
    if (isLoading || isAuthenticating) {
      return <div>Loading...</div>;
    }
    if (!isLoggedIn) {
      return <div>Log in!</div>;
    }
    return <h1>You are looged in!</h1>;
  }
}

const Me = withRouter(MeComp);

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
            <Route path="/me">
              <Me />
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
